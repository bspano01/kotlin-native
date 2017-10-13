/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.backend.konan

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.descriptors.allParameters
import org.jetbrains.kotlin.backend.common.descriptors.isSuspend
import org.jetbrains.kotlin.backend.common.ir.ir2stringWhole
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.peek
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.backend.konan.descriptors.*
import org.jetbrains.kotlin.backend.konan.ir.IrPrivateFunctionCallImpl
import org.jetbrains.kotlin.backend.konan.ir.IrSuspendableExpression
import org.jetbrains.kotlin.backend.konan.ir.IrSuspensionPoint
import org.jetbrains.kotlin.backend.konan.llvm.*
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.descriptors.IrBuiltinOperatorDescriptorBase
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.util.getArguments
import org.jetbrains.kotlin.ir.visitors.*
import org.jetbrains.kotlin.konan.target.CompilerOutputKind
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.OverridingUtil
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.*

// TODO: Exceptions, Arrays.

// Devirtualization analysis is performed using Variable Type Analysis algorithm.
// See <TODO: link to the article> for details.
internal object Devirtualization {

    private val DEBUG = 0

    private class VariableValues {
        val elementData = HashMap<VariableDescriptor, MutableSet<IrExpression>>()

        fun addEmpty(variable: VariableDescriptor) =
                elementData.getOrPut(variable, { mutableSetOf<IrExpression>() })

        fun add(variable: VariableDescriptor, element: IrExpression) =
                elementData.get(variable)?.add(element)

        fun add(variable: VariableDescriptor, elements: Set<IrExpression>) =
                elementData.get(variable)?.addAll(elements)

        fun get(variable: VariableDescriptor): Set<IrExpression>? =
                elementData[variable]

        fun computeClosure() {
            elementData.forEach { key, _ ->
                add(key, computeValueClosure(key))
            }
        }

        // Computes closure of all possible values for given variable.
        private fun computeValueClosure(value: VariableDescriptor): Set<IrExpression> {
            val result = mutableSetOf<IrExpression>()
            val seen = mutableSetOf<VariableDescriptor>()
            dfs(value, seen, result)
            return result
        }

        private fun dfs(value: VariableDescriptor, seen: MutableSet<VariableDescriptor>, result: MutableSet<IrExpression>) {
            seen += value
            val elements = elementData[value]
                    ?: return
            for (element in elements) {
                if (element !is IrGetValue)
                    result += element
                else {
                    val descriptor = element.descriptor
                    if (descriptor is VariableDescriptor && !seen.contains(descriptor))
                        dfs(descriptor, seen, result)
                }
            }
        }
    }

    private fun IrTypeOperator.isCast() =
            this == IrTypeOperator.CAST || this == IrTypeOperator.IMPLICIT_CAST || this == IrTypeOperator.SAFE_CAST

    private class ExpressionValuesExtractor(val returnableBlockValues: Map<IrReturnableBlock, List<IrExpression>>,
                                            val suspendableExpressionValues: Map<IrSuspendableExpression, List<IrSuspensionPoint>>) {

        fun forEachValue(expression: IrExpression, block: (IrExpression) -> Unit) {
            when (expression) {
                is IrReturnableBlock -> returnableBlockValues[expression]!!.forEach { forEachValue(it, block) }

                is IrSuspendableExpression ->
                    (suspendableExpressionValues[expression]!! + expression.result).forEach { forEachValue(it, block) }

                is IrSuspensionPoint -> {
                    forEachValue(expression.result, block)
                    forEachValue(expression.resumeResult, block)
                }

                is IrContainerExpression -> {
                    if (expression.statements.isNotEmpty())
                        forEachValue(expression.statements.last() as IrExpression, block)
                }

                is IrWhen -> expression.branches.forEach { forEachValue(it.result, block) }

                is IrMemberAccessExpression -> block(expression)

                is IrGetValue -> block(expression)

                is IrGetField -> block(expression)

                is IrVararg -> /* Sometimes, we keep vararg till codegen phase (for constant arrays). */
                    block(expression)

                is IrConst<*> -> block(expression)

                is IrTypeOperatorCall -> {
                    if (!expression.operator.isCast())
                        block(expression)
                    else { // Propagate cast to sub-values.
                        forEachValue(expression.argument) { value ->
                            with(expression) {
                                block(IrTypeOperatorCallImpl(startOffset, endOffset, type, operator, typeOperand, value))
                            }
                        }
                    }
                }

                is IrTry -> {
                    forEachValue(expression.tryResult, block)
                    expression.catches.forEach { forEachValue(it.result, block) }
                }

                is IrGetObjectValue -> block(expression)

                is IrFunctionReference -> block(expression)

                is IrSetField -> block(expression)

                else -> {
                    if ((expression.type.isUnit() || expression.type.isNothing())) {
                        block(IrGetObjectValueImpl(expression.startOffset, expression.endOffset,
                                expression.type, (expression.type.constructor.declarationDescriptor as ClassDescriptor)))
                    }
                    else TODO(ir2stringWhole(expression))
                }
            }
        }
    }

    private fun computeErasure(type: KotlinType, erasure: MutableList<KotlinType>) {
        val descriptor = type.constructor.declarationDescriptor
        when (descriptor) {
            is ClassDescriptor -> erasure += type.makeNotNullable()
            is TypeParameterDescriptor -> {
                descriptor.upperBounds.forEach {
                    computeErasure(it, erasure)
                }
            }
            else -> TODO(this.toString())
        }
    }

    private fun KotlinType.erasure(): List<KotlinType> {
        val result = mutableListOf<KotlinType>()
        computeErasure(this, result)
        return result
    }

    private fun MemberScope.getOverridingOf(function: FunctionDescriptor) = when (function) {
        is PropertyGetterDescriptor ->
            this.getContributedVariables(function.correspondingProperty.name, NoLookupLocation.FROM_BACKEND)
                    .firstOrNull { OverridingUtil.overrides(it, function.correspondingProperty) }?.getter

        is PropertySetterDescriptor ->
            this.getContributedVariables(function.correspondingProperty.name, NoLookupLocation.FROM_BACKEND)
                    .firstOrNull { OverridingUtil.overrides(it, function.correspondingProperty) }?.setter

        else -> this.getContributedFunctions(function.name, NoLookupLocation.FROM_BACKEND)
                .firstOrNull { OverridingUtil.overrides(it, function) }
    }

    private val KotlinType.isFinal get() = (constructor.declarationDescriptor as ClassDescriptor).modality == Modality.FINAL

    internal class FunctionTemplateBody(val nodes: List<Node>, val returns: Node.TempVariable) {

        abstract class Type {
            object Virtual: Declared(false, true)

            class External(val name: String): Type() {
                override fun equals(other: Any?): Boolean {
                    if (this === other) return true
                    if (other !is External) return false

                    return name == other.name
                }

                override fun hashCode(): Int {
                    return name.hashCode()
                }

                override fun toString(): String {
                    return "ExternalType(name='$name')"
                }
            }

            abstract class Declared(val isFinal: Boolean, val isAbstract: Boolean): Type() {
                val superTypes = mutableListOf<Type>()
                val vtable = mutableListOf<FunctionId>()
                val itable = mutableMapOf<Long, FunctionId>()
            }

            class Public(val name: String, isFinal: Boolean, isAbstract: Boolean): Declared(isFinal, isAbstract) {
                override fun equals(other: Any?): Boolean {
                    if (this === other) return true
                    if (other !is Public) return false

                    return name == other.name
                }

                override fun hashCode(): Int {
                    return name.hashCode()
                }

                override fun toString(): String {
                    return "PublicType(name='$name')"
                }
            }

            class Private(val name: String, val index: Int, isFinal: Boolean, isAbstract: Boolean): Declared(isFinal, isAbstract) {
                override fun equals(other: Any?): Boolean {
                    if (this === other) return true
                    if (other !is Private) return false

                    return index == other.index
                }

                override fun hashCode(): Int {
                    return index
                }

                override fun toString(): String {
                    return "PrivateType(index=$index, name='$name')"
                }
            }
        }

        class Module(val name: String) {
            var numberOfFunctions = 0
        }

        abstract class FunctionId {
            class External(val name: String): FunctionId() {
                override fun equals(other: Any?): Boolean {
                    if (this === other) return true
                    if (other !is External) return false

                    return name == other.name
                }

                override fun hashCode(): Int {
                    return name.hashCode()
                }

                override fun toString(): String {
                    return "ExternalFunction(name='$name')"
                }
            }

            abstract class Declared(val module: Module, val symbolTableIndex: Int): FunctionId()

            class Public(val name: String, module: Module, symbolTableIndex: Int) : Declared(module, symbolTableIndex) {
                override fun equals(other: Any?): Boolean {
                    if (this === other) return true
                    if (other !is Public) return false

                    return name == other.name
                }

                override fun hashCode(): Int {
                    return name.hashCode()
                }

                override fun toString(): String {
                    return "PublicFunction(name='$name')"
                }
            }

            class Private(val name: String, val index: Int, module: Module, symbolTableIndex: Int) : Declared(module, symbolTableIndex) {
                override fun equals(other: Any?): Boolean {
                    if (this === other) return true
                    if (other !is Private) return false

                    return index == other.index
                }

                override fun hashCode(): Int {
                    return index
                }

                override fun toString(): String {
                    return "PrivateFunction(index=$index, name='$name')"
                }
            }
        }

        data class Field(val type: Type?, val name: String)

        class Edge(val castToType: Type?) {

            lateinit var node: Node

            constructor(node: Node, castToType: Type?): this(castToType) {
                this.node = node
            }
        }

        sealed class Node {
            class Parameter(val index: Int): Node()

            class Const(val type: Type): Node()

            open class Call(val callee: FunctionId, val arguments: List<Edge>, val returnType: Type): Node()

            class StaticCall(callee: FunctionId, arguments: List<Edge>, returnType: Type,
                             val receiverType: Type?): Call(callee, arguments, returnType)

            class NewObject(constructor: FunctionId, arguments: List<Edge>, type: Type): Call(constructor, arguments, type)

            open class VirtualCall(callee: FunctionId, arguments: List<Edge>, returnType: Type,
                                   val receiverType: Type, val callSite: IrCall?): Call(callee, arguments, returnType)

            class VtableCall(callee: FunctionId, receiverType: Type, val calleeVtableIndex: Int,
                             arguments: List<Edge>, returnType: Type, callSite: IrCall?)
                : VirtualCall(callee, arguments, returnType, receiverType, callSite)

            class ItableCall(callee: FunctionId, receiverType: Type, val calleeHash: Long,
                             arguments: List<Edge>, returnType: Type, callSite: IrCall?)
                : VirtualCall(callee, arguments, returnType, receiverType, callSite)

            class Singleton(val type: Type): Node()

            class FieldRead(val receiver: Edge?, val field: Field): Node()

            class FieldWrite(val receiver: Edge?, val field: Field, val value: Edge): Node()

            class Variable(values: List<Edge>): Node() {
                val values = mutableListOf<Edge>().also { it += values }
            }

            class TempVariable(val values: List<Edge>): Node()
        }
    }

    private fun FunctionDescriptor.externalOrIntrinsic() = isExternal || isIntrinsic || (this is IrBuiltinOperatorDescriptorBase)

    private fun ClassDescriptor.isFinal() = modality == Modality.FINAL && kind != ClassKind.ENUM_CLASS

    private class SymbolTable(val context: Context, val irModule: IrModuleFragment, val module: FunctionTemplateBody.Module) {
        val classMap = mutableMapOf<ClassDescriptor, FunctionTemplateBody.Type>()
        val functionMap = mutableMapOf<CallableDescriptor, FunctionTemplateBody.FunctionId>()

        var privateTypeIndex = 0
        var privateFunIndex = 0
        var couldBeCalledVirtuallyIndex = 0

        init {
            irModule.accept(object : IrElementVisitorVoid {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitFunction(declaration: IrFunction) {
                    declaration.body?.let { mapFunction(declaration.descriptor) }
                }

                override fun visitField(declaration: IrField) {
                    declaration.initializer?.let { mapFunction(declaration.descriptor) }
                }

                override fun visitClass(declaration: IrClass) {
                    declaration.acceptChildrenVoid(this)

                    mapClass(declaration.descriptor)
                }
            }, data = null)
        }

        fun mapClass(descriptor: ClassDescriptor): FunctionTemplateBody.Type {
//            if (descriptor.module.name == Name.special("<forward declarations>"))
//                return mapClass(descriptor.getSuperClassNotAny()!!)
//            if (descriptor.isObjCClass())
//                return mapClass(context.interopBuiltIns.objCPointerHolder)
            if (descriptor.module.name == Name.special("<forward declarations>") || descriptor.isObjCClass())
                return FunctionTemplateBody.Type.Virtual

            val name = descriptor.fqNameSafe.asString()
            if (descriptor.module != irModule.descriptor)
                return classMap.getOrPut(descriptor) { FunctionTemplateBody.Type.External(name) }

            classMap[descriptor]?.let { return it }

            val isFinal = descriptor.isFinal()
            val isAbstract = descriptor.isAbstract()
            val type = if (descriptor.isExported())
                FunctionTemplateBody.Type.Public(name, isFinal, isAbstract)
            else
                FunctionTemplateBody.Type.Private(name, privateTypeIndex++, isFinal, isAbstract)
            if (!descriptor.isInterface) {
                val vtableBuilder = context.getVtableBuilder(descriptor)
                type.vtable += vtableBuilder.vtableEntries.map { mapFunction(it.getImplementation(context)) }
                if (!isAbstract) {
                    vtableBuilder.methodTableEntries.forEach {
                        type.itable.put(
                                it.overriddenDescriptor.functionName.localHash.value,
                                mapFunction(it.getImplementation(context))
                        )
                    }
                }
            }
            classMap.put(descriptor, type)
            type.superTypes += descriptor.defaultType.immediateSupertypes().map { mapType(it) }
            return type
        }

        fun mapType(type: KotlinType) =
                mapClass(type.erasure().single().constructor.declarationDescriptor as ClassDescriptor)

        // TODO: use from LlvmDeclarations.
        private fun getFqName(descriptor: DeclarationDescriptor): FqName {
            if (descriptor is PackageFragmentDescriptor) {
                return descriptor.fqName
            }

            val containingDeclaration = descriptor.containingDeclaration
            val parent = if (containingDeclaration != null) {
                getFqName(containingDeclaration)
            } else {
                FqName.ROOT
            }

            val localName = descriptor.name
            return parent.child(localName)
        }

        fun mapFunction(descriptor: CallableDescriptor) = descriptor.original.let {
            functionMap.getOrPut(it) {
                when (it) {
                    is PropertyDescriptor ->
                        FunctionTemplateBody.FunctionId.Private("${it.symbolName}_init", privateFunIndex++, module, -1)

                    is FunctionDescriptor -> {
                        if (it.module != irModule.descriptor || it.externalOrIntrinsic())
                            FunctionTemplateBody.FunctionId.External(it.symbolName)
                        else {
                            val isAbstract = it.modality == Modality.ABSTRACT
                            val classDescriptor = it.containingDeclaration as? ClassDescriptor
                            val placeToFunctionsTable = !isAbstract && it !is ConstructorDescriptor && classDescriptor != null
                                    && classDescriptor.kind != ClassKind.ANNOTATION_CLASS
                                    && (it.isOverridableOrOverrides || it.name.asString().contains("<bridge-") || !classDescriptor.isFinal())
                            if (placeToFunctionsTable)
                                ++module.numberOfFunctions
                            val symbolTableIndex = if (!placeToFunctionsTable) -1 else couldBeCalledVirtuallyIndex++
                            if (it.isExported())
                                FunctionTemplateBody.FunctionId.Public(it.symbolName, module, symbolTableIndex)
                            else
                                FunctionTemplateBody.FunctionId.Private(getFqName(it).asString() + "#internal", privateFunIndex++, module, symbolTableIndex)
                        }
                    }

                    else -> error("Unknown descriptor: $it")
                }
            }
        }
    }

    private class FunctionTemplate(val id: FunctionTemplateBody.FunctionId,
                                   val numberOfParameters: Int,
                                   val body: FunctionTemplateBody) {

        companion object {
            fun create(context: Context,
                       descriptor: CallableDescriptor,
                       expressions: List<IrExpression>,
                       variableValues: VariableValues,
                       returnValues: List<IrExpression>,
                       expressionValuesExtractor: ExpressionValuesExtractor,
                       symbolTable: SymbolTable) =
                    Builder(context, expressionValuesExtractor, variableValues, symbolTable, descriptor, expressions, returnValues).template

            private class Builder(val context: Context,
                                  val expressionValuesExtractor: ExpressionValuesExtractor,
                                  val variableValues: VariableValues,
                                  val symbolTable: SymbolTable,
                                  val descriptor: CallableDescriptor,
                                  expressions: List<IrExpression>,
                                  returnValues: List<IrExpression>) {

                private val coroutineImplDescriptor = context.getInternalClass("CoroutineImpl")
                private val doResumeFunctionDescriptor = coroutineImplDescriptor.unsubstitutedMemberScope
                        .getContributedFunctions(Name.identifier("doResume"), NoLookupLocation.FROM_BACKEND).single()
                private val getContinuationSymbol = context.ir.symbols.getContinuation

                private val allParameters = (descriptor as? FunctionDescriptor)?.allParameters ?: emptyList()
                private val templateParameters =
                        allParameters.withIndex().associateBy({ it.value }, { FunctionTemplateBody.Node.Parameter(it.index) })

                private val continuationParameter =
                        if (descriptor.isSuspend)
                            FunctionTemplateBody.Node.Parameter(allParameters.size)
                        else {
                            if (doResumeFunctionDescriptor in descriptor.overriddenDescriptors)
                                templateParameters[descriptor.dispatchReceiverParameter!!]
                            else null
                        }
                private fun getContinuation() = continuationParameter ?: error("Function $descriptor has no continuation parameter")

                private val nodes = mutableMapOf<IrExpression, FunctionTemplateBody.Node>()
                private val variables = variableValues.elementData.keys.associateBy(
                        { it },
                        { FunctionTemplateBody.Node.Variable(mutableListOf()) }
                )
                val template: FunctionTemplate

                init {
                    for (expression in expressions) {
                        getNode(expression)
                    }
                    val returns = FunctionTemplateBody.Node.TempVariable(returnValues.map { expressionToEdge(it) })
                    variables.forEach { descriptor, node ->
                            variableValues.elementData[descriptor]!!.forEach {
                                node.values += expressionToEdge(it)
                            }
                    }
                    val allNodes = nodes.values + variables.values + templateParameters.values + returns +
                            (if (descriptor.isSuspend) listOf(continuationParameter!!) else emptyList())

                    template = FunctionTemplate(symbolTable.mapFunction(descriptor),
                            templateParameters.size + if (descriptor.isSuspend) 1 else 0, FunctionTemplateBody(allNodes.distinct().toList(), returns))
                }

                private fun expressionToEdge(expression: IrExpression) =
                        if (expression is IrTypeOperatorCall && expression.operator.isCast())
                            FunctionTemplateBody.Edge(getNode(expression.argument), symbolTable.mapType(expression.typeOperand))
                        else FunctionTemplateBody.Edge(getNode(expression), null)

                private fun getNode(expression: IrExpression): FunctionTemplateBody.Node {
                    if (expression is IrGetValue) {
                        val descriptor = expression.descriptor
                        if (descriptor is ParameterDescriptor)
                            return templateParameters[descriptor]!!
                        return variables[descriptor as VariableDescriptor]!!
                    }
                    return nodes.getOrPut(expression) {
                        if (DEBUG > 1) {
                            println("Converting expression")
                            println(ir2stringWhole(expression))
                        }
                        val values = mutableListOf<IrExpression>()
                        expressionValuesExtractor.forEachValue(expression) { values += it }
                        if (values.size != 1) {
                            FunctionTemplateBody.Node.TempVariable(values.map { expressionToEdge(it) })
                        }
                        else {
                            val value = values[0]
                            if (value != expression) {
                                val edge = expressionToEdge(value)
                                if (edge.castToType == null)
                                    edge.node
                                else
                                    FunctionTemplateBody.Node.TempVariable(listOf(edge))
                            } else {
                                when (value) {
                                    is IrGetValue -> getNode(value)

                                    is IrVararg,
                                    is IrConst<*>,
                                    is IrFunctionReference -> FunctionTemplateBody.Node.Const(symbolTable.mapType(value.type))

                                    is IrGetObjectValue -> FunctionTemplateBody.Node.Singleton(symbolTable.mapType(value.type))

                                    is IrCall -> {
                                        if (value.symbol == getContinuationSymbol) {
                                            getContinuation()
                                        } else {
                                            val callee = value.descriptor.target
                                            val arguments = value.getArguments()
                                                    .map { expressionToEdge(it.second) }
                                                    .let {
                                                        if (callee.isSuspend)
                                                            it + FunctionTemplateBody.Edge(getContinuation(), null)
                                                        else
                                                            it
                                                    }
                                            if (callee is ConstructorDescriptor) {
                                                FunctionTemplateBody.Node.NewObject(
                                                        symbolTable.mapFunction(callee),
                                                        arguments,
                                                        symbolTable.mapClass(callee.constructedClass)
                                                )
                                            } else {
                                                if (callee.isOverridable && value.superQualifier == null) {
                                                    val owner = callee.containingDeclaration as ClassDescriptor
                                                    val vTableBuilder = context.getVtableBuilder(owner)
                                                    if (owner.isInterface) {
                                                        FunctionTemplateBody.Node.ItableCall(
                                                                symbolTable.mapFunction(callee),
                                                                symbolTable.mapClass(owner),
                                                                callee.functionName.localHash.value,
                                                                arguments,
                                                                symbolTable.mapType(callee.returnType!!),
                                                                value
                                                        )
                                                    } else {
                                                        val vtableIndex = vTableBuilder.vtableIndex(callee)
                                                        assert(vtableIndex >= 0, { "Unable to find function $callee in vTable of $owner" })
                                                        FunctionTemplateBody.Node.VtableCall(
                                                                symbolTable.mapFunction(callee),
                                                                symbolTable.mapClass(owner),
                                                                vtableIndex,
                                                                arguments,
                                                                symbolTable.mapType(callee.returnType!!),
                                                                value
                                                        )
                                                    }
                                                } else {
                                                    val actualCallee = value.superQualifier.let {
                                                        if (it == null)
                                                            callee
                                                        else
                                                            it.unsubstitutedMemberScope.getOverridingOf(callee)?.target ?: callee
                                                    }
                                                    FunctionTemplateBody.Node.StaticCall(
                                                            symbolTable.mapFunction(actualCallee),
                                                            arguments,
                                                            symbolTable.mapType(actualCallee.returnType!!),
                                                            actualCallee.dispatchReceiverParameter?.let { symbolTable.mapType(it.type) }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    is IrDelegatingConstructorCall -> {
                                        val thiz = IrGetValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                                                (descriptor as ConstructorDescriptor).constructedClass.thisAsReceiverParameter)
                                        val arguments = listOf(thiz) + value.getArguments().map { it.second }
                                        FunctionTemplateBody.Node.StaticCall(
                                                symbolTable.mapFunction(value.descriptor),
                                                arguments.map { expressionToEdge(it) },
                                                symbolTable.mapClass(context.builtIns.unit),
                                                symbolTable.mapType(thiz.type)
                                        )
                                    }

                                    is IrGetField -> {
                                        val receiver = value.receiver?.let { expressionToEdge(it) }
                                        val receiverType = value.receiver?.let { symbolTable.mapType(it.type) }
                                        FunctionTemplateBody.Node.FieldRead(
                                                receiver,
                                                FunctionTemplateBody.Field(
                                                        receiverType,
                                                        value.descriptor.name.asString()
                                                )
                                        )
                                    }

                                    is IrSetField -> {
                                        val receiver = value.receiver?.let { expressionToEdge(it) }
                                        val receiverType = value.receiver?.let { symbolTable.mapType(it.type) }
                                        FunctionTemplateBody.Node.FieldWrite(
                                                receiver,
                                                FunctionTemplateBody.Field(
                                                        receiverType,
                                                        value.descriptor.name.asString()
                                                ),
                                                expressionToEdge(value.value)
                                        )
                                    }

                                    is IrTypeOperatorCall -> {
                                        assert(!value.operator.isCast(), { "Casts should've been handled earlier" })
                                        expressionToEdge(value.argument) // Put argument as a separate vertex.
                                        FunctionTemplateBody.Node.Const(symbolTable.mapType(value.type)) // All operators except casts are basically constants.
                                    }

                                    else -> TODO("Unknown expression: ${ir2stringWhole(value)}")
                                }
                            }
                        }
                    }
                }
            }
        }

        fun printNode(node: FunctionTemplateBody.Node, ids: Map<FunctionTemplateBody.Node, Int>) {
            when (node) {
                is FunctionTemplateBody.Node.Const ->
                    println("        CONST ${node.type}")

                is FunctionTemplateBody.Node.Parameter ->
                    println("        PARAM ${node.index}")

                is FunctionTemplateBody.Node.Singleton ->
                    println("        SINGLETON ${node.type}")

                is FunctionTemplateBody.Node.StaticCall -> {
                    println("        STATIC CALL ${node.callee}")
                    node.arguments.forEach {
                        print("            ARG #${ids[it.node]!!}")
                        if (it.castToType == null)
                            println()
                        else
                            println(" CASTED TO ${it.castToType}")
                    }
                }

                is FunctionTemplateBody.Node.VtableCall -> {
                    println("        VIRTUAL CALL ${node.callee}")
                    println("            RECEIVER: ${node.receiverType}")
                    println("            VTABLE INDEX: ${node.calleeVtableIndex}")
                    node.arguments.forEach {
                        print("            ARG #${ids[it.node]!!}")
                        if (it.castToType == null)
                            println()
                        else
                            println(" CASTED TO ${it.castToType}")
                    }
                }

                is FunctionTemplateBody.Node.ItableCall -> {
                    println("        INTERFACE CALL ${node.callee}")
                    println("            RECEIVER: ${node.receiverType}")
                    println("            METHOD HASH: ${node.calleeHash}")
                    node.arguments.forEach {
                        print("            ARG #${ids[it.node]!!}")
                        if (it.castToType == null)
                            println()
                        else
                            println(" CASTED TO ${it.castToType}")
                    }
                }

                is FunctionTemplateBody.Node.NewObject -> {
                    println("        NEW OBJECT ${node.callee}")
                    println("        TYPE ${node.returnType}")
                    node.arguments.forEach {
                        print("            ARG #${ids[it.node]!!}")
                        if (it.castToType == null)
                            println()
                        else
                            println(" CASTED TO ${it.castToType}")
                    }
                }

                is FunctionTemplateBody.Node.FieldRead -> {
                    println("        FIELD READ ${node.field}")
                    print("            RECEIVER #${node.receiver?.node?.let { ids[it]!! } ?: "null"}")
                    if (node.receiver?.castToType == null)
                        println()
                    else
                        println(" CASTED TO ${node.receiver.castToType}")
                }

                is FunctionTemplateBody.Node.FieldWrite -> {
                    println("        FIELD WRITE ${node.field}")
                    print("            RECEIVER #${node.receiver?.node?.let { ids[it]!! } ?: "null"}")
                    if (node.receiver?.castToType == null)
                        println()
                    else
                        println(" CASTED TO ${node.receiver.castToType}")
                    print("            VALUE #${ids[node.value.node]!!}")
                    if (node.value.castToType == null)
                        println()
                    else
                        println(" CASTED TO ${node.value.castToType}")
                }

                is FunctionTemplateBody.Node.TempVariable -> {
                    println("        TEMP VAR")
                    node.values.forEach {
                        print("            VAL #${ids[it.node]!!}")
                        if (it.castToType == null)
                            println()
                        else
                            println(" CASTED TO ${it.castToType}")
                    }

                }

                is FunctionTemplateBody.Node.Variable -> {
                    println("        VARIABLE")
                    node.values.forEach {
                        if (ids[it.node] == null) {
                            println("            ZUGZUGZUG: ${it.node}")
                        }
                        print("            VAL #${ids[it.node]!!}")
                        if (it.castToType == null)
                            println()
                        else
                            println(" CASTED TO ${it.castToType}")
                    }

                }

                else -> {
                    println("        UNKNOWN: ${node::class.java}")
                }
            }
        }

        fun debugOutput() {
            println("FUNCTION TEMPLATE $id")
            println("Params: $numberOfParameters")
            val ids = body.nodes.withIndex().associateBy({ it.value }, { it.index })
            body.nodes.forEach {
                println("    NODE #${ids[it]!!}")
                printNode(it, ids)
            }
            println("    RETURNS")
            printNode(body.returns, ids)
        }
    }

    private class IntraproceduralAnalysisResult(val functionTemplates: Map<FunctionTemplateBody.FunctionId, FunctionTemplate>,
                                                val symbolTable: SymbolTable)

    private class IntraproceduralAnalysis(val context: Context) {

        // Possible values of a returnable block.
        private val returnableBlockValues = mutableMapOf<IrReturnableBlock, MutableList<IrExpression>>()

        // All suspension points within specified suspendable expression.
        private val suspendableExpressionValues = mutableMapOf<IrSuspendableExpression, MutableList<IrSuspensionPoint>>()

        private val expressionValuesExtractor = ExpressionValuesExtractor(returnableBlockValues, suspendableExpressionValues)

        fun analyze(irModule: IrModuleFragment): IntraproceduralAnalysisResult {
            val templates = mutableMapOf<FunctionTemplateBody.FunctionId, FunctionTemplate>()
            val module = FunctionTemplateBody.Module(irModule.descriptor.name.asString())
            val symbolTable = SymbolTable(context, irModule, module)
            irModule.accept(object : IrElementVisitorVoid {

                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitFunction(declaration: IrFunction) {
                    declaration.body?.let {
                        if (DEBUG > 1) {
                            println("Analysing function ${declaration.descriptor}")
                            println("IR: ${ir2stringWhole(declaration)}")
                        }
                        analyze(declaration.descriptor, it)
                    }
                }

                override fun visitField(declaration: IrField) {
                    declaration.initializer?.let {
                        if (DEBUG > 1) {
                            println("Analysing global field ${declaration.descriptor}")
                            println("IR: ${ir2stringWhole(declaration)}")
                        }
                        analyze(declaration.descriptor, it)
                    }
                }

                private fun analyze(descriptor: CallableDescriptor, body: IrElement) {
                    // Find all interesting expressions, variables and functions.
                    val visitor = ElementFinderVisitor()
                    body.acceptVoid(visitor)

                    if (DEBUG > 1) {
                        println("FIRST PHASE")
                        visitor.variableValues.elementData.forEach { t, u ->
                            println("VAR $t:")
                            u.forEach {
                                println("    ${ir2stringWhole(it)}")
                            }
                        }
                        visitor.expressions.forEach { t ->
                            println("EXP ${ir2stringWhole(t)}")
                        }
                    }

                    // Compute transitive closure of possible values for variables.
                    visitor.variableValues.computeClosure()

                    if (DEBUG > 1) {
                        println("SECOND PHASE")
                        visitor.variableValues.elementData.forEach { t, u ->
                            println("VAR $t:")
                            u.forEach {
                                println("    ${ir2stringWhole(it)}")
                            }
                        }
                    }

                    val functionTemplate = FunctionTemplate.create(context, descriptor,
                            visitor.expressions, visitor.variableValues,
                            visitor.returnValues, expressionValuesExtractor, symbolTable)

                    if (DEBUG > 1) {
                        functionTemplate.debugOutput()
                    }

                    templates.put(functionTemplate.id, functionTemplate)
                }
            }, data = null)

            if (DEBUG > 1) {
                println("SYMBOL TABLE:")
                symbolTable.classMap.forEach { descriptor, type ->
                    println("    DESCRIPTOR: $descriptor")
                    println("    TYPE: $type")
                    if (type !is FunctionTemplateBody.Type.Declared)
                        return@forEach
                    println("        SUPER TYPES:")
                    type.superTypes.forEach { println("            $it") }
                    println("        VTABLE:")
                    type.vtable.forEach { println("            $it") }
                    println("        ITABLE:")
                    type.itable.forEach { println("            ${it.key} -> ${it.value}") }
                }
            }

            return IntraproceduralAnalysisResult(templates, symbolTable)
        }

        private inner class ElementFinderVisitor : IrElementVisitorVoid {

            val expressions = mutableListOf<IrExpression>()
            val variableValues = VariableValues()
            val returnValues = mutableListOf<IrExpression>()

            private val returnableBlocks = mutableMapOf<FunctionDescriptor, IrReturnableBlock>()
            private val suspendableExpressionStack = mutableListOf<IrSuspendableExpression>()

            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            private fun assignVariable(variable: VariableDescriptor, value: IrExpression) {
                expressionValuesExtractor.forEachValue(value) {
                    variableValues.add(variable, it)
                }
            }

            override fun visitExpression(expression: IrExpression) {
                when (expression) {
                    is IrMemberAccessExpression,
                    is IrGetField,
                    is IrGetObjectValue,
                    is IrVararg,
                    is IrConst<*>,
                    is IrTypeOperatorCall ->
                        expressions += expression
                }

                if (expression is IrReturnableBlock) {
                    returnableBlocks.put(expression.descriptor, expression)
                    returnableBlockValues.put(expression, mutableListOf())
                }
                if (expression is IrSuspendableExpression) {
                    suspendableExpressionStack.push(expression)
                    suspendableExpressionValues.put(expression, mutableListOf())
                }
                if (expression is IrSuspensionPoint)
                    suspendableExpressionValues[suspendableExpressionStack.peek()!!]!!.add(expression)
                super.visitExpression(expression)
                if (expression is IrReturnableBlock)
                    returnableBlocks.remove(expression.descriptor)
                if (expression is IrSuspendableExpression)
                    suspendableExpressionStack.pop()
            }

            override fun visitSetField(expression: IrSetField) {
                expressions += expression
                super.visitSetField(expression)
            }

            // TODO: hack to overcome bad code in InlineConstructorsTransformation.
            private val FQ_NAME_INLINE_CONSTRUCTOR = FqName("konan.internal.InlineConstructor")

            override fun visitReturn(expression: IrReturn) {
                val returnableBlock = returnableBlocks[expression.returnTarget]
                if (returnableBlock != null) {
                    returnableBlockValues[returnableBlock]!!.add(expression.value)
                } else { // Non-local return.
                    if (!expression.type.isUnit()) {
                        if (!expression.returnTarget.annotations.hasAnnotation(FQ_NAME_INLINE_CONSTRUCTOR)) // Not inline constructor.
                            returnValues += expression.value
                    }
                }
                super.visitReturn(expression)
            }

            override fun visitSetVariable(expression: IrSetVariable) {
                super.visitSetVariable(expression)
                assignVariable(expression.descriptor, expression.value)
            }

            override fun visitVariable(declaration: IrVariable) {
                variableValues.addEmpty(declaration.descriptor)
                super.visitVariable(declaration)
                declaration.initializer?.let { assignVariable(declaration.descriptor, it) }
            }
        }
    }

    private class ExternalAnalysisResult(val publicTypes: Map<String, FunctionTemplateBody.Type.Public>,
                                         val publicFunctions: Map<String, FunctionTemplateBody.FunctionId.Public>,
                                         val functionTemplates: Map<FunctionTemplateBody.FunctionId, FunctionTemplate>)

    private class InterproceduralAnalysis(val context: Context,
                                          val externalAnalysisResult: ExternalAnalysisResult,
                                          val intraproceduralAnalysisResult: IntraproceduralAnalysisResult) {

        private val symbolTable = intraproceduralAnalysisResult.symbolTable

        class ConstraintGraph {

            private var nodesCount = 0

            val nodes = mutableListOf<Node>()
            val voidNode = Node.Ordinary(nextId(), "Void").also { nodes.add(it) }
            val functions = mutableMapOf<FunctionTemplateBody.FunctionId, Function>()
            val concreteClasses = mutableMapOf<FunctionTemplateBody.Type.Declared, Node>()
            val virtualClasses = mutableMapOf<FunctionTemplateBody.Type.Declared, Node>()
            val fields = mutableMapOf<FunctionTemplateBody.Field, Node>() // Do not distinguish receivers.
            val virtualCallSiteReceivers = mutableMapOf<IrCall, Triple<Node, List<DevirtualizedCallee>, FunctionTemplateBody.FunctionId>>()

            fun nextId(): Int = nodesCount++

            fun addNode(node: Node) = nodes.add(node)

            class Function(val id: FunctionTemplateBody.FunctionId, val parameters: Array<Node>, val returns: Node)

            enum class TypeKind {
                CONCRETE,
                VIRTUAL
            }

            data class Type(val type: FunctionTemplateBody.Type.Declared, val kind: TypeKind) {
                companion object {
                    fun concrete(type: FunctionTemplateBody.Type.Declared): Type {
                        if (type.isAbstract && type.isFinal)
                            println("ZUGZUG: $type")
                        return Type(type, if (type.isAbstract) TypeKind.VIRTUAL else TypeKind.CONCRETE)
                    }

                    fun virtual(type: FunctionTemplateBody.Type.Declared): Type {
                        if (type.isAbstract && type.isFinal)
                            println("ZUGZUG: $type")
                        return Type(type, if (type.isFinal) TypeKind.CONCRETE else TypeKind.VIRTUAL)
                    }
                }
            }

            sealed class Node(val id: Int) {
                val types = mutableSetOf<Type>()
                val edges = mutableSetOf<Node>()
                val reversedEdges = mutableSetOf<Node>()
                val castEdges = mutableSetOf<CastEdge>()
                val reversedCastEdges = mutableSetOf<CastEdge>()
                var priority = -1

                fun addEdge(node: Node) {
                    edges += node
                    node.reversedEdges += this
                }

                fun addCastEdge(edge: CastEdge) {
                    castEdges += edge
                    edge.node.reversedCastEdges += CastEdge(this, edge.castToType, edge.functionId)
                }

                class Source(id: Int, val name: String, type: Type): Node(id) {
                    init {
                        types += type
                    }

                    override fun toString(): String {
                        return "Source(name='$name', types='${types.joinToString { it.toString() }}')"
                    }
                }

                class Ordinary(id: Int, val name: String) : Node(id) {
                    override fun toString(): String {
                        return "Ordinary(name='$name', types='${types.joinToString { it.toString() }}')"
                    }
                }

                data class CastEdge(val node: Node, val castToType: FunctionTemplateBody.Type.Declared, val functionId: FunctionTemplateBody.FunctionId)

//                // Corresponds to an edge with a cast on it.
//                class Cast(id: Int, val castToType: FunctionTemplateBody.Type.Declared, val functionId: FunctionTemplateBody.FunctionId) : Node(id) {
//                    override fun toString() = "Cast(castToType=$castToType)\$$functionId"
//                }
            }

            class MultiNode(val nodes: Set<Node>)

            class Condensation(val topologicalOrder: List<MultiNode>)

            private inner class CondensationBuilder {
                private val visited = mutableSetOf<Node>()
                private val order = mutableListOf<Node>()
                private val nodeToMultiNodeMap = mutableMapOf<Node, MultiNode>()
                private val multiNodesOrder = mutableListOf<MultiNode>()

                fun build(): Condensation {
                    // First phase.
                    nodes.forEach {
                        if (!visited.contains(it))
                            findOrder(it)
                    }

                    // Second phase.
                    visited.clear()
                    val multiNodes = mutableListOf<MultiNode>()
                    order.reversed().forEach {
                        if (!visited.contains(it)) {
                            val nodes = mutableSetOf<Node>()
                            paint(it, nodes)
                            multiNodes += MultiNode(nodes)
                        }
                    }

                    // Topsort of built condensation.
                    multiNodes.forEach { multiNode ->
                        multiNode.nodes.forEach { nodeToMultiNodeMap.put(it, multiNode) }
                    }
                    visited.clear()
                    multiNodes.forEach {
                        if (!visited.contains(it.nodes.first()))
                            findMultiNodesOrder(it)
                    }

                    return Condensation(multiNodesOrder)
                }

                private fun findOrder(node: Node) {
                    visited += node
                    node.edges.forEach {
                        if (!visited.contains(it))
                            findOrder(it)
                    }
                    order += node
                }

                private fun paint(node: Node, multiNode: MutableSet<Node>) {
                    visited += node
                    multiNode += node
                    node.reversedEdges.forEach {
                        if (!visited.contains(it))
                            paint(it, multiNode)
                    }
                }

                private fun findMultiNodesOrder(node: MultiNode) {
                    visited.addAll(node.nodes)
                    node.nodes.forEach {
                        it.edges.forEach {
                            if (!visited.contains(it))
                                findMultiNodesOrder(nodeToMultiNodeMap[it]!!)
                        }
                    }
                    multiNodesOrder += node
                }
            }

            fun buildCondensation() = CondensationBuilder().build()
        }

        private val constraintGraph = ConstraintGraph()

        private fun FunctionTemplateBody.Type.resolved(): FunctionTemplateBody.Type.Declared {
            if (this is FunctionTemplateBody.Type.Declared) return this
            val name = (this as FunctionTemplateBody.Type.External).name
            return externalAnalysisResult.publicTypes[name] ?: error("Unable to resolve exported type $name")
        }

        private fun FunctionTemplateBody.FunctionId.resolved(): FunctionTemplateBody.FunctionId {
            if (this is FunctionTemplateBody.FunctionId.External)
                return externalAnalysisResult.publicFunctions[this.name] ?: this
            return this
        }

        private fun FunctionTemplateBody.Type.Declared.isSubtypeOf(other: FunctionTemplateBody.Type.Declared): Boolean {
            return this == other || this.superTypes.any { it.resolved().isSubtypeOf(other) }
        }

        private fun getInstantiatingClasses(functionTemplates: Map<FunctionTemplateBody.FunctionId, FunctionTemplate>)
                : Set<FunctionTemplateBody.Type.Declared> {
            val instantiatingClasses = mutableSetOf<FunctionTemplateBody.Type.Declared>()
            functionTemplates.values
                    .asSequence()
                    .flatMap { it.body.nodes.asSequence() }
                    .forEach {
                        if (it is FunctionTemplateBody.Node.NewObject)
                            instantiatingClasses += it.returnType.resolved()
                        else if (it is FunctionTemplateBody.Node.Singleton)
                            instantiatingClasses += it.type.resolved()
                    }
            instantiatingClasses += symbolTable.mapClass(context.builtIns.string).resolved()
            return instantiatingClasses
        }

        fun analyze(irModule: IrModuleFragment): Map<IrCall, DevirtualizedCallSite> {
            // Rapid Type Analysis: find all instantiations and conservatively estimate call graph.
            val functionTemplates = intraproceduralAnalysisResult.functionTemplates + externalAnalysisResult.functionTemplates
            val instantiatingClasses = getInstantiatingClasses(functionTemplates)

            val nodesMap = mutableMapOf<FunctionTemplateBody.Node, ConstraintGraph.Node>()
            val variables = mutableMapOf<FunctionTemplateBody.Node.Variable, ConstraintGraph.Node>()
            functionTemplates.entries.forEach {
                buildFunctionConstraintGraph(it.key, nodesMap, variables, instantiatingClasses)
                if (DEBUG > 0) {
                    println("CONSTRAINT GRAPH FOR ${it.key}")
                    val ids = it.value.body.nodes.withIndex().associateBy({ it.value }, { it.index })
                    for (node in it.value.body.nodes) {
                        println("FT NODE #${ids[node]}")
                        it.value.printNode(node, ids)
                        val constraintNode = nodesMap[node] ?: variables[node] ?: break
                        println("       CG NODE #${constraintNode.id}: $constraintNode")
                        println()
                    }
                    println("Returns: #${ids[it.value.body.returns]}")
                    println()
                }
            }
            val rootSet = getRootSet(irModule)
            rootSet.filterIsInstance<FunctionDescriptor>()
                    .forEach {
                        val functionId = symbolTable.mapFunction(it).resolved()
                        if (constraintGraph.functions[functionId] == null)
                            println("BUGBUGBUG: $it, $functionId")
                        val function = constraintGraph.functions[functionId]!!
                        it.allParameters.forEachIndexed { index, parameter ->
                            parameter.type.erasure().forEach {
                                val parameterType = symbolTable.mapClass(it.constructor.declarationDescriptor as ClassDescriptor).resolved()
                                val node = constraintGraph.virtualClasses.getOrPut(parameterType) {
                                    ConstraintGraph.Node.Source(constraintGraph.nextId(), "Class\$$parameterType", ConstraintGraph.Type.virtual(parameterType)).also {
                                        constraintGraph.addNode(it)
                                    }
                                }
                                node.addEdge(function.parameters[index])
                            }
                        }
                    }
            if (DEBUG > 0) {
                println("FULL CONSTRAINT GRAPH")
                constraintGraph.nodes.forEach {
                    println("    NODE #${it.id}: $it")
                    it.edges.forEach {
                        println("        EDGE: #${it.id}z")
                    }
                    it.types.forEach { println("        TYPE: $it") }
                }
            }
            constraintGraph.nodes.forEach {
                if (it is ConstraintGraph.Node.Source)
                    assert(it.reversedEdges.isEmpty(), { "A source node #${it.id} has incoming edges" })
            }
//            println("CONSTRAINT GRAPH: ${constraintGraph.nodes.size} nodes, ${constraintGraph.nodes.sumBy { it.edges.size }} edges")
            val condensation = constraintGraph.buildCondensation()
            val topologicalOrder = condensation.topologicalOrder.reversed()
            if (DEBUG > 0) {
                println("CONDENSATION")
                topologicalOrder.forEachIndexed { index, multiNode ->
                    println("    MULTI-NODE #$index")
                    multiNode.nodes.forEach {
                        println("        #${it.id}: $it")
                    }
                }
            }
            topologicalOrder.forEachIndexed { index, multiNode ->
                multiNode.nodes.forEach { it.priority = index }
            }
            // Handle all 'right-directed' edges.
            topologicalOrder.forEachIndexed { index, multiNode ->
                if (multiNode.nodes.size == 1 && multiNode.nodes.first() is ConstraintGraph.Node.Source)
                    return@forEachIndexed
                val types = mutableSetOf<ConstraintGraph.Type>()
                for (node in multiNode.nodes) {
                    node.reversedEdges.forEach { types += it.types }
                    node.reversedCastEdges
                            .filter { it.node.priority < node.priority }
                            .forEach { (source, castToType) -> types += source.types.filter { it.type.isSubtypeOf(castToType) } }
                }
                for (node in multiNode.nodes)
                    node.types += types
            }
            val badEdges = mutableListOf<Pair<ConstraintGraph.Node, ConstraintGraph.Node.CastEdge>>()
            for (node in constraintGraph.nodes) {
                node.castEdges
                        .filter { it.node.priority < node.priority } // Contradicts topological order.
                        .forEach { badEdges += node to it }
            }
            badEdges.sortBy { it.second.node.priority } // Heuristic.

            do {
                fun propagateType(node: ConstraintGraph.Node, type: ConstraintGraph.Type) {
                    node.types += type
                    node.edges
                            .filterNot { it.types.contains(type) }
                            .forEach { propagateType(it, type) }
                    node.castEdges
                            .filterNot { it.node.types.contains(type) }
                            .filter { type.type.isSubtypeOf(it.castToType) }
                            .forEach { propagateType(it.node, type) }
                }

                var end = true
                for ((sourceNode, edge) in badEdges) {
                    val distNode = edge.node
                    sourceNode.types
                            .filter { !distNode.types.contains(it) }
                            .filter { it.type.isSubtypeOf(edge.castToType) }
                            .forEach {
                                end = false
                                propagateType(distNode, it)
                            }
                }
            } while (!end)
            if (DEBUG > 0) {
                topologicalOrder.forEachIndexed { index, multiNode ->
                    println("Types of multi-node #$index")
                    multiNode.nodes.forEach {
                        println("    Node #${it.id}")
                        it.types.forEach { println("        $it") }
                    }
                }
            }
//            condensation.topologicalOrder.reversed().forEachIndexed { index, multiNode ->
//                if (multiNode.nodes.size == 1 && multiNode.nodes.first() is ConstraintGraph.Node.Source)
//                    return@forEachIndexed
//                while (true) {
//                    // TODO: Optimize.
//                    var somethingChanged = false
//                    for (node in multiNode.nodes) {
//                        val types = mutableSetOf<ConstraintGraph.Type>()
//                        node.reversedEdges.forEach { types += it.types }
//                        if (node is ConstraintGraph.Node.Cast) {
//                            //var wasVirtualType = false
//                            val badTypes = mutableSetOf<ConstraintGraph.Type>()
//                            types.forEach {
//                                if (!it.type.isSubtypeOf(node.castToType)) {
//                                    badTypes += it
////                                if (it.kind == ConstraintGraph.TypeKind.VIRTUAL)
////                                    wasVirtualType = true
//                                }
//                            }
//                            types -= badTypes
////                        if (wasVirtualType)
////                            node.types += ConstraintGraph.Type.virtual(node.castToType)
//                        }
//                        if (node.types.size != types.size)
//                            somethingChanged = true
//                        else {
//                            if (types.any { !node.types.contains(it) })
//                                somethingChanged = true
//                        }
//                        node.types.clear()
//                        node.types += types
//                    }
//                    if (!somethingChanged) break
//                }
//                if (DEBUG > 0) {
//                    println("Types of multi-node #$index")
//                    multiNode.nodes.forEach {
//                        println("    Node #${it.id}")
//                        it.types.forEach { println("        $it") }
//                    }
//                }
//            }
            val result = mutableMapOf<IrCall, Pair<DevirtualizedCallSite, FunctionTemplateBody.FunctionId>>()
            val nothing = symbolTable.mapClass(context.builtIns.nothing)
            functionTemplates.values
                    .asSequence()
                    .flatMap { it.body.nodes.asSequence() }
                    .filterIsInstance<FunctionTemplateBody.Node.VirtualCall>()
                    .forEach {
                        if (nodesMap[it] == null) {
                            println("BUGBUGBUG: $it")
                        }
                        assert (nodesMap[it] != null, { "Node for virtual call $it has not been built" })
                        val callSite = it.callSite ?: return@forEach
                        val receiver = constraintGraph.virtualCallSiteReceivers[callSite]
                        if (receiver == null || receiver.first.types.isEmpty()
                                || receiver.first.types.any { it.kind == ConstraintGraph.TypeKind.VIRTUAL })
                            return@forEach
                        val possibleReceivers = receiver.first.types.filterNot { it.type == nothing }
                        val map = receiver.second.associateBy({ it.receiverType }, { it })
                        result.put(callSite, DevirtualizedCallSite(possibleReceivers.map {
                            if (map[it.type] == null) {
                                println("BUGBUGBUG: $it, ${it.type.isFinal}, ${it.type.isAbstract}")
                                println(receiver.first)
                                println("Actual receiver types:")
                                possibleReceivers.forEach { println("    $it") }
                                println("Possible receiver types:")
                                map.keys.forEach { println("    $it") }
                            }
                            val devirtualizedCallee = map[it.type]!!
                            val callee = devirtualizedCallee.callee
                            if (callee is FunctionTemplateBody.FunctionId.Declared && callee.symbolTableIndex < 0)
                                error("Function ${devirtualizedCallee.receiverType}.$callee cannot be called virtually," +
                                        " but actually is at call site: ${ir2stringWhole(callSite)}")
//
//                            println("RECEIVER: #${receiver.first.id}")
//                            println("TYPES:")
//                            receiver.first.types.forEach {
//                                println(it)
//                            }

                            devirtualizedCallee
                        }) to receiver.third)
                    }
            if (DEBUG > 0) {
                result.forEach { callSite, devirtualizedCallSite ->
                    if (devirtualizedCallSite.first.possibleCallees.isNotEmpty()) {
                        println("DEVIRTUALIZED")
                        println("FUNCTION: ${devirtualizedCallSite.second}")
                        println("CALL SITE: ${ir2stringWhole(callSite)}")
                        println("POSSIBLE RECEIVERS:")
                        devirtualizedCallSite.first.possibleCallees.forEach { println("    TYPE: ${it.receiverType}") }
                        devirtualizedCallSite.first.possibleCallees.forEach { println("    FUN: ${it.callee}") }
                        println()
                    }
                }
            }
            return result.asSequence().associateBy({ it.key }, { it.value.first })
        }

        private fun getRootSet(irModule: IrModuleFragment): Set<CallableDescriptor> {
            val rootSet = mutableSetOf<CallableDescriptor>()
            val hasMain = context.config.configuration.get(KonanConfigKeys.PRODUCE) == CompilerOutputKind.PROGRAM
            if (hasMain)
                rootSet.add(findMainEntryPoint(context)!!)
            irModule.accept(object: IrElementVisitorVoid {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitField(declaration: IrField) {
                    declaration.initializer?.let {
                        // Global field.
                        rootSet += declaration.descriptor
                    }
                }

                override fun visitFunction(declaration: IrFunction) {
                    if (!hasMain && declaration.descriptor.isExported()
                            && declaration.descriptor.modality != Modality.ABSTRACT
                            && !declaration.descriptor.externalOrIntrinsic()
                            && declaration.descriptor.kind != CallableMemberDescriptor.Kind.FAKE_OVERRIDE)
                        // For a library take all visible functions.
                        rootSet += declaration.descriptor
                }
            }, data = null)
            return rootSet
        }

        private fun buildFunctionConstraintGraph(id: FunctionTemplateBody.FunctionId,
                                                 nodes: MutableMap<FunctionTemplateBody.Node, ConstraintGraph.Node>,
                                                 variables: MutableMap<FunctionTemplateBody.Node.Variable, ConstraintGraph.Node>,
                                                 instantiatingClasses: Collection<FunctionTemplateBody.Type.Declared>)
                : ConstraintGraph.Function? {
            if (id is FunctionTemplateBody.FunctionId.External) return null
            constraintGraph.functions[id]?.let { return it }

            val template = (intraproceduralAnalysisResult.functionTemplates[id] ?: externalAnalysisResult.functionTemplates[id])
                    ?: error("Unknown function: $id")
            val body = template.body
            val parameters = Array<ConstraintGraph.Node>(template.numberOfParameters) {
                ConstraintGraph.Node.Ordinary(constraintGraph.nextId(), "Param#$it\$$id").also {
                    constraintGraph.addNode(it)
                }
            }
            val returnsNode = ConstraintGraph.Node.Ordinary(constraintGraph.nextId(), "Returns\$$id").also {
                constraintGraph.addNode(it)
            }
            val function = ConstraintGraph.Function(id, parameters, returnsNode)
            constraintGraph.functions[id] = function
            body.nodes.forEach { templateNodeToConstraintNode(function, it, nodes, variables, instantiatingClasses) }
            nodes[body.returns]!!.addEdge(returnsNode)
            return function
        }

        private fun edgeToConstraintNode(function: ConstraintGraph.Function,
                                         edge: FunctionTemplateBody.Edge,
                                         functionNodesMap: MutableMap<FunctionTemplateBody.Node, ConstraintGraph.Node>,
                                         variables: MutableMap<FunctionTemplateBody.Node.Variable, ConstraintGraph.Node>,
                                         instantiatingClasses: Collection<FunctionTemplateBody.Type.Declared>): ConstraintGraph.Node {
            val result = templateNodeToConstraintNode(function, edge.node, functionNodesMap, variables, instantiatingClasses)
            val castToType = edge.castToType ?: return result
            val castNode = ConstraintGraph.Node.Ordinary(constraintGraph.nextId(), "Cast\$${function.id}")
            constraintGraph.addNode(castNode)
            val castEdge = ConstraintGraph.Node.CastEdge(castNode, castToType.resolved(), function.id)
            result.addCastEdge(castEdge)
            return castNode
//            return edge.castToType?.let {
//                val castNode = ConstraintGraph.Node.Cast(constraintGraph.nextId(), it.resolved(), function.id)
//                constraintGraph.addNode(castNode)
//                result.addEdge(castNode)
//                castNode
//            } ?: result
        }

        /**
         * Takes a function template's node and creates a constraint graph node corresponding to it.
         * Also creates all necessary edges.
         */
        private fun templateNodeToConstraintNode(function: ConstraintGraph.Function,
                                                 node: FunctionTemplateBody.Node,
                                                 functionNodesMap: MutableMap<FunctionTemplateBody.Node, ConstraintGraph.Node>,
                                                 variables: MutableMap<FunctionTemplateBody.Node.Variable, ConstraintGraph.Node>,
                                                 instantiatingClasses: Collection<FunctionTemplateBody.Type.Declared>)
                : ConstraintGraph.Node {

            fun edgeToConstraintNode(edge: FunctionTemplateBody.Edge): ConstraintGraph.Node =
                    edgeToConstraintNode(function, edge, functionNodesMap, variables, instantiatingClasses)

            fun argumentToConstraintNode(argument: Any): ConstraintGraph.Node =
                    when (argument) {
                        is ConstraintGraph.Node -> argument
                        is FunctionTemplateBody.Edge -> edgeToConstraintNode(argument)
                        else -> error("Unexpected argument: $argument")
                    }

            fun doCall(callee: ConstraintGraph.Function, arguments: List<Any>): ConstraintGraph.Node {
                assert(callee.parameters.size == arguments.size, { "Function ${callee.id} takes ${callee.parameters.size} but caller ${function.id} provided ${arguments.size}" })
                callee.parameters.forEachIndexed { index, parameter ->
                    val argument = argumentToConstraintNode(arguments[index])
                    argument.addEdge(parameter)
                }
                return callee.returns
            }

            fun doCall(callee: FunctionTemplateBody.FunctionId,
                       arguments: List<Any>,
                       returnType: FunctionTemplateBody.Type.Declared,
                       receiverType: FunctionTemplateBody.Type.Declared?): ConstraintGraph.Node {
                val calleeConstraintGraph = buildFunctionConstraintGraph(callee.resolved(), functionNodesMap, variables, instantiatingClasses)
                return if (calleeConstraintGraph == null) {
                    constraintGraph.concreteClasses.getOrPut(returnType) {
                        ConstraintGraph.Node.Source(constraintGraph.nextId(), "Class\$$returnType", ConstraintGraph.Type.concrete(returnType)).also {
                            constraintGraph.addNode(it)
                        }
                    }
                } else {
                    if (receiverType == null)
                        doCall(calleeConstraintGraph, arguments)
                    else {
                        val receiverNode = argumentToConstraintNode(arguments[0])
//                        val castedReceiver = ConstraintGraph.Node.Cast(constraintGraph.nextId(), receiverType, function.id).also {
//                            constraintGraph.addNode(it)
//                        }
//                        receiverNode.addEdge(castedReceiver)
                        val castedReceiver = ConstraintGraph.Node.Ordinary(constraintGraph.nextId(), "CastedReceiver\$${function.id}")
                        constraintGraph.addNode(castedReceiver)
                        val castedEdge = ConstraintGraph.Node.CastEdge(castedReceiver, receiverType, function.id)
                        receiverNode.addCastEdge(castedEdge)
                        doCall(calleeConstraintGraph, listOf(castedReceiver) + arguments.drop(1))
                    }
                }
            }

            if (node is FunctionTemplateBody.Node.Variable) {
                var variableNode = variables[node]
                if (variableNode == null) {
                    variableNode = ConstraintGraph.Node.Ordinary(constraintGraph.nextId(), "Variable\$${function.id}").also {
                        constraintGraph.addNode(it)
                    }
                    variables[node] = variableNode
                    for (value in node.values) {
                        edgeToConstraintNode(value).addEdge(variableNode)
                    }
                }
                return variableNode
            }

            return functionNodesMap.getOrPut(node) {
                when (node) {
                    is FunctionTemplateBody.Node.Const ->
                        ConstraintGraph.Node.Source(constraintGraph.nextId(), "Ordinary\$${function.id}", ConstraintGraph.Type.concrete(node.type.resolved())).also {
                            constraintGraph.addNode(it)
                        }

                    is FunctionTemplateBody.Node.Parameter ->
                        function.parameters[node.index]

                    is FunctionTemplateBody.Node.StaticCall ->
                        doCall(node.callee, node.arguments, node.returnType.resolved(), node.receiverType?.resolved())

                    is FunctionTemplateBody.Node.NewObject -> {
                        val returnType = node.returnType.resolved()
                        val instanceNode = constraintGraph.concreteClasses.getOrPut(returnType) {
                            val instanceType = ConstraintGraph.Type.concrete(returnType)
                            ConstraintGraph.Node.Source(constraintGraph.nextId(), "Class\$${node.returnType}", instanceType).also {
                                constraintGraph.addNode(it)
                            }
                        }
                        doCall(node.callee, listOf(instanceNode) + node.arguments, returnType, null)
                        instanceNode
                    }

                    is FunctionTemplateBody.Node.VirtualCall -> {
                        val callee = node.callee
                        if (node.receiverType == FunctionTemplateBody.Type.Virtual)
                            constraintGraph.voidNode
                        else {
                            val receiverType = node.receiverType.resolved()
                            if (DEBUG > 0) {
                                println("Virtual call")
                                println("Caller: ${function.id}")
                                println("Callee: $callee")
                                println("Receiver type: $receiverType")
                            }
                            // TODO: optimize by building type hierarchy.
                            val possibleReceiverTypes = instantiatingClasses.filter { it.isSubtypeOf(receiverType) }
                            val callees = possibleReceiverTypes.map {
                                when (node) {
                                    is FunctionTemplateBody.Node.VtableCall ->
                                        it.vtable[node.calleeVtableIndex]

                                    is FunctionTemplateBody.Node.ItableCall -> {
                                        if (it.itable[node.calleeHash] == null) {
                                            println("BUGBUGBUG: $it, HASH=${node.calleeHash}")
                                            it.itable.forEach { hash, impl ->
                                                println("HASH: $hash, IMPL: $impl")
                                            }
                                        }
                                        it.itable[node.calleeHash]!!
                                    }

                                    else -> error("Unreachable")
                                }
                            }
                            if (DEBUG > 0) {
                                println("Possible callees:")
                                callees.forEach { println("$it") }
                                println()
                            }
                            if (callees.isEmpty())
                                constraintGraph.voidNode
                            else {
                                val returnType = node.returnType.resolved()
                                val receiverNode = edgeToConstraintNode(node.arguments[0])
//                                val castedReceiver = ConstraintGraph.Node.Cast(constraintGraph.nextId(), receiverType, function.id).also {
//                                    constraintGraph.addNode(it)
//                                }
//                                receiverNode.addEdge(castedReceiver)
                                val castedReceiver = ConstraintGraph.Node.Ordinary(constraintGraph.nextId(), "CastedReceiver\$${function.id}")
                                constraintGraph.addNode(castedReceiver)
                                val castedEdge = ConstraintGraph.Node.CastEdge(castedReceiver, receiverType, function.id)
                                receiverNode.addCastEdge(castedEdge)
                                val result = if (callees.size == 1) {
                                    doCall(callees[0], listOf(castedReceiver) + node.arguments.drop(1), returnType, possibleReceiverTypes.single())
                                } else {
                                    val returns = ConstraintGraph.Node.Ordinary(constraintGraph.nextId(), "VirtualCallReturns\$${function.id}").also {
                                        constraintGraph.addNode(it)
                                    }
                                    callees.forEachIndexed { index, actualCallee ->
                                        doCall(actualCallee, listOf(castedReceiver) + node.arguments.drop(1), returnType, possibleReceiverTypes[index]).addEdge(returns)
                                    }
                                    returns
                                }
                                val devirtualizedCallees = possibleReceiverTypes.mapIndexed { index, possibleReceiverType ->
                                    DevirtualizedCallee(possibleReceiverType, callees[index])
                                }
                                node.callSite?.let {
                                    constraintGraph.virtualCallSiteReceivers[it] = Triple(castedReceiver, devirtualizedCallees, function.id)
                                }
                                result
                            }
                        }
                    }

                    is FunctionTemplateBody.Node.Singleton -> {
                        val type = node.type.resolved()
                        constraintGraph.concreteClasses.getOrPut(type) {
                            ConstraintGraph.Node.Source(constraintGraph.nextId(), "Class\$$type", ConstraintGraph.Type.concrete(type)).also {
                                constraintGraph.addNode(it)
                            }
                        }
                    }

                    is FunctionTemplateBody.Node.FieldRead ->
                        constraintGraph.fields.getOrPut(node.field) {
                            ConstraintGraph.Node.Ordinary(constraintGraph.nextId(), "Field\$${node.field}").also {
                                constraintGraph.addNode(it)
                            }
                        }

                    is FunctionTemplateBody.Node.FieldWrite -> {
                        val fieldNode = constraintGraph.fields.getOrPut(node.field) {
                            ConstraintGraph.Node.Ordinary(constraintGraph.nextId(), "Field\$${node.field}").also {
                                constraintGraph.addNode(it)
                            }
                        }
                        edgeToConstraintNode(node.value).addEdge(fieldNode)
                        constraintGraph.voidNode
                    }

                    is FunctionTemplateBody.Node.TempVariable ->
                        node.values.map { edgeToConstraintNode(it) }.let { values ->
                            ConstraintGraph.Node.Ordinary(constraintGraph.nextId(), "TempVar\$${function.id}").also { node ->
                                constraintGraph.addNode(node)
                                values.forEach { it.addEdge(node) }
                            }
                        }

                    is FunctionTemplateBody.Node.Variable ->
                        error("Variables should've been handled earlier")

                    else -> error("Unreachable")
                }
            }
        }
    }

    internal class DevirtualizedCallee(val receiverType: FunctionTemplateBody.Type, val callee: FunctionTemplateBody.FunctionId)

    internal class DevirtualizedCallSite(val possibleCallees: List<DevirtualizedCallee>)

    internal class DevirtualizationAnalysisResult(val privateFunctions: List<FunctionDescriptor>,
                                                  val devirtualizedCallSites: Map<IrCall, DevirtualizedCallSite>)

    internal fun analyze(irModule: IrModuleFragment, context: Context) : DevirtualizationAnalysisResult {
        val intraproceduralAnalysisResult = IntraproceduralAnalysis(context).analyze(irModule)

        val moduleBuilder = context.devirtualizationAnalysisResult.value
        val symbolTableBuilder = ModuleDevirtualizationAnalysisResult.SymbolTable.newBuilder()
        val symbolTable = intraproceduralAnalysisResult.symbolTable
//            val alltypes = symbolTable.functionMap.entries.toTypedArray()
//            println(symbolTable.privateFunIndex)
//            for (i in alltypes.indices)
//                for (j in i+1 until alltypes.size)
//                    if (alltypes[i].value == alltypes[j].value) {
//                        println("${alltypes[i].key} = ${alltypes[j].key}")
//                        println("${alltypes[i].value} = ${alltypes[j].value}")
//                    }
        val typeMap = (symbolTable.classMap.values + FunctionTemplateBody.Type.Virtual).distinct().withIndex().associateBy({ it.value }, { it.index })
        val functionIdMap = symbolTable.functionMap.values.distinct().withIndex().associateBy({ it.value }, { it.index })
        println("TYPES: ${typeMap.size}, FUNCTIONS: ${functionIdMap.size}, PRIVATE FUNCTIONS: ${functionIdMap.keys.count { it is FunctionTemplateBody.FunctionId.Private }}, FUNCTION TABLE SIZE: ${symbolTable.couldBeCalledVirtuallyIndex}")
        for (type in typeMap.entries.sortedBy { it.value }.map { it.key }) {

            fun buildTypeIntestines(type: FunctionTemplateBody.Type.Declared): ModuleDevirtualizationAnalysisResult.DeclaredType.Builder {
                val result = ModuleDevirtualizationAnalysisResult.DeclaredType.newBuilder()
                result.isFinal = type.isFinal
                result.isAbstract = type.isAbstract
                result.addAllSuperTypes(type.superTypes.map { typeMap[it]!! })
                result.addAllVtable(type.vtable.map { functionIdMap[it]!! })
                type.itable.forEach { hash, functionId ->
                    val itableSlotBuilder = ModuleDevirtualizationAnalysisResult.ItableSlot.newBuilder()
                    itableSlotBuilder.hash = hash
                    itableSlotBuilder.impl = functionIdMap[functionId]!!
                    result.addItable(itableSlotBuilder)
                }
                return result
            }

            val typeBuilder = ModuleDevirtualizationAnalysisResult.Type.newBuilder()
            when (type) {
                FunctionTemplateBody.Type.Virtual -> {
                    typeBuilder.setVirtual(1)
                }

                is FunctionTemplateBody.Type.External -> {
                    val externalTypeBuilder = ModuleDevirtualizationAnalysisResult.ExternalType.newBuilder()
                    externalTypeBuilder.name = type.name
                    typeBuilder.setExternal(externalTypeBuilder)
                }

                is FunctionTemplateBody.Type.Public -> {
                    val publicTypeBuilder = ModuleDevirtualizationAnalysisResult.PublicType.newBuilder()
                    publicTypeBuilder.name = type.name
                    publicTypeBuilder.setIntestines(buildTypeIntestines(type))
                    typeBuilder.setPublic(publicTypeBuilder)
                }

                is FunctionTemplateBody.Type.Private -> {
                    val privateTypeBuilder = ModuleDevirtualizationAnalysisResult.PrivateType.newBuilder()
                    privateTypeBuilder.name = type.name
                    privateTypeBuilder.index = type.index
                    privateTypeBuilder.setIntestines(buildTypeIntestines(type))
                    typeBuilder.setPrivate(privateTypeBuilder)
                }
            }
            symbolTableBuilder.addTypes(typeBuilder)
        }
        for (functionId in functionIdMap.entries.sortedBy { it.value }.map { it.key }) {
            val functionIdBuilder = ModuleDevirtualizationAnalysisResult.FunctionId.newBuilder()
            when (functionId) {
                is FunctionTemplateBody.FunctionId.External -> {
                    val externalFunctionIdBuilder = ModuleDevirtualizationAnalysisResult.ExternalFunctionId.newBuilder()
                    externalFunctionIdBuilder.name = functionId.name
                    functionIdBuilder.setExternal(externalFunctionIdBuilder)
                }

                is FunctionTemplateBody.FunctionId.Public -> {
                    val publicFunctionIdBuilder = ModuleDevirtualizationAnalysisResult.PublicFunctionId.newBuilder()
                    publicFunctionIdBuilder.name = functionId.name
                    publicFunctionIdBuilder.index = functionId.symbolTableIndex
                    functionIdBuilder.setPublic(publicFunctionIdBuilder)
                }

                is FunctionTemplateBody.FunctionId.Private -> {
                    val privateFunctionIdBuilder = ModuleDevirtualizationAnalysisResult.PrivateFunctionId.newBuilder()
                    privateFunctionIdBuilder.name = functionId.name
                    privateFunctionIdBuilder.index = functionId.symbolTableIndex
                    functionIdBuilder.setPrivate(privateFunctionIdBuilder)
                }
            }
            symbolTableBuilder.addFunctionIds(functionIdBuilder)
        }
        moduleBuilder.setSymbolTable(symbolTableBuilder)
        for (functionTemplate in intraproceduralAnalysisResult.functionTemplates.values) {
            val functionTemplateBuilder = ModuleDevirtualizationAnalysisResult.FunctionTemplate.newBuilder()
            functionTemplateBuilder.id = functionIdMap[functionTemplate.id]!!
            functionTemplateBuilder.numberOfParameters = functionTemplate.numberOfParameters
            val bodyBuilder = ModuleDevirtualizationAnalysisResult.FunctionTemplateBody.newBuilder()
            val body = functionTemplate.body
            val nodeMap = body.nodes.withIndex().associateBy({ it.value }, { it.index })

            fun buildEdge(edge: FunctionTemplateBody.Edge): ModuleDevirtualizationAnalysisResult.Edge.Builder {
                val result = ModuleDevirtualizationAnalysisResult.Edge.newBuilder()
                    if (nodeMap[edge.node] == null) {
                        println("ZUGZUGZUG")
                        functionTemplate.printNode(edge.node, nodeMap)
                        println()
                        functionTemplate.debugOutput()
                    }
                result.node = nodeMap[edge.node]!!
                edge.castToType?.let { result.castToType = typeMap[it]!! }
                return result
            }

            fun buildCall(call: FunctionTemplateBody.Node.Call): ModuleDevirtualizationAnalysisResult.Call.Builder {
                val result = ModuleDevirtualizationAnalysisResult.Call.newBuilder()
                result.callee = functionIdMap[call.callee]!!
                result.returnType = typeMap[call.returnType]!!
                call.arguments.forEach {
                    result.addArguments(buildEdge(it))
                }
                return result
            }

            fun buildVirtualCall(virtualCall: FunctionTemplateBody.Node.VirtualCall): ModuleDevirtualizationAnalysisResult.VirtualCall.Builder {
                val result = ModuleDevirtualizationAnalysisResult.VirtualCall.newBuilder()
                result.setCall(buildCall(virtualCall))
                result.receiverType = typeMap[virtualCall.receiverType]!!
                return result
            }

            fun buildField(field: FunctionTemplateBody.Field): ModuleDevirtualizationAnalysisResult.Field.Builder {
                val result = ModuleDevirtualizationAnalysisResult.Field.newBuilder()
                field.type?.let { result.type = typeMap[it]!! }
                result.name = field.name
                return result
            }

            for (node in nodeMap.entries.sortedBy { it.value }.map { it.key }) {
                val nodeBuilder = ModuleDevirtualizationAnalysisResult.Node.newBuilder()
                when (node) {
                    is FunctionTemplateBody.Node.Parameter -> {
                        val parameterBuilder = ModuleDevirtualizationAnalysisResult.Parameter.newBuilder()
                        parameterBuilder.index = node.index
                        nodeBuilder.setParameter(parameterBuilder)
                    }

                    is FunctionTemplateBody.Node.Const -> {
                        val constBuilder = ModuleDevirtualizationAnalysisResult.Const.newBuilder()
                        constBuilder.type = typeMap[node.type]!!
                        nodeBuilder.setConst(constBuilder)
                    }

                    is FunctionTemplateBody.Node.StaticCall -> {
                        val staticCallBuilder = ModuleDevirtualizationAnalysisResult.StaticCall.newBuilder()
                        staticCallBuilder.setCall(buildCall(node))
                        if (node.receiverType != null)
                            staticCallBuilder.receiverType = typeMap[node.receiverType]!!
                        nodeBuilder.setStaticCall(staticCallBuilder)
                    }

                    is FunctionTemplateBody.Node.NewObject -> {
                        val newObjectBuilder = ModuleDevirtualizationAnalysisResult.NewObject.newBuilder()
                        newObjectBuilder.setCall(buildCall(node))
                        nodeBuilder.setNewObject(newObjectBuilder)
                    }

                    is FunctionTemplateBody.Node.VtableCall -> {
                        val vTableCallBuilder = ModuleDevirtualizationAnalysisResult.VtableCall.newBuilder()
                        vTableCallBuilder.setVirtualCall(buildVirtualCall(node))
                        vTableCallBuilder.calleeVtableIndex = node.calleeVtableIndex
                        nodeBuilder.setVtableCall(vTableCallBuilder)
                    }

                    is FunctionTemplateBody.Node.ItableCall -> {
                        val iTableCallBuilder = ModuleDevirtualizationAnalysisResult.ItableCall.newBuilder()
                        iTableCallBuilder.setVirtualCall(buildVirtualCall(node))
                        iTableCallBuilder.calleeHash = node.calleeHash
                        nodeBuilder.setItableCall(iTableCallBuilder)
                    }

                    is FunctionTemplateBody.Node.Singleton -> {
                        val singletonBuilder = ModuleDevirtualizationAnalysisResult.Singleton.newBuilder()
                        singletonBuilder.type = typeMap[node.type]!!
                        nodeBuilder.setSingleton(singletonBuilder)
                    }

                    is FunctionTemplateBody.Node.FieldRead -> {
                        val fieldReadBuilder = ModuleDevirtualizationAnalysisResult.FieldRead.newBuilder()
                        node.receiver?.let { fieldReadBuilder.setReceiver(buildEdge(it)) }
                        fieldReadBuilder.setField(buildField(node.field))
                        nodeBuilder.setFieldRead(fieldReadBuilder)
                    }

                    is FunctionTemplateBody.Node.FieldWrite -> {
                        val fieldWriteBuilder = ModuleDevirtualizationAnalysisResult.FieldWrite.newBuilder()
                        node.receiver?.let { fieldWriteBuilder.setReceiver(buildEdge(it)) }
                        fieldWriteBuilder.setField(buildField(node.field))
                        fieldWriteBuilder.setValue(buildEdge(node.value))
                        nodeBuilder.setFieldWrite(fieldWriteBuilder)
                    }

                    is FunctionTemplateBody.Node.Variable -> {
                        val variableBuilder = ModuleDevirtualizationAnalysisResult.Variable.newBuilder()
                        node.values.forEach {
                            variableBuilder.addValues(buildEdge(it))
                        }
                        nodeBuilder.setVariable(variableBuilder)
                    }

                    is FunctionTemplateBody.Node.TempVariable -> {
                        val tempVariableBuilder = ModuleDevirtualizationAnalysisResult.TempVariable.newBuilder()
                        node.values.forEach {
                            tempVariableBuilder.addValues(buildEdge(it))
                        }
                        nodeBuilder.setTempVariable(tempVariableBuilder)
                    }
                }
                bodyBuilder.addNodes(nodeBuilder)
            }
            bodyBuilder.returns = nodeMap[body.returns]!!
            functionTemplateBuilder.setBody(bodyBuilder)
            moduleBuilder.addFunctionTemplates(functionTemplateBuilder)
        }

        var privateTypeIndex = intraproceduralAnalysisResult.symbolTable.privateTypeIndex
        var privateFunIndex = intraproceduralAnalysisResult.symbolTable.privateFunIndex
        val publicTypesMap = mutableMapOf<String, FunctionTemplateBody.Type.Public>()
        val publicFunctionsMap = mutableMapOf<String, FunctionTemplateBody.FunctionId.Public>()
        val functionTemplates = mutableMapOf<FunctionTemplateBody.FunctionId, FunctionTemplate>()
        val specifics = context.config.configuration.get(CommonConfigurationKeys.LANGUAGE_VERSION_SETTINGS)!!
        context.config.libraries.forEach { library ->
            val libraryDevirtualizationAnalysis = library.devirtualizationAnalysis
            //if (DEBUG > 0) {
                println("Devirtualization analysis size for lib '${library.libraryName}': ${libraryDevirtualizationAnalysis?.size ?: 0}")
            //}
            if (libraryDevirtualizationAnalysis != null) {
                val module = FunctionTemplateBody.Module(library.moduleDescriptor(specifics).name.asString())
                val moduleDevirtualizationAnalysisResult = ModuleDevirtualizationAnalysisResult.Module.parseFrom(libraryDevirtualizationAnalysis)

                val symbolTable = moduleDevirtualizationAnalysisResult.symbolTable
                val types = symbolTable.typesList.map {
                    when (it.typeCase) {
                        ModuleDevirtualizationAnalysisResult.Type.TypeCase.VIRTUAL ->
                            FunctionTemplateBody.Type.Virtual

                        ModuleDevirtualizationAnalysisResult.Type.TypeCase.EXTERNAL ->
                            FunctionTemplateBody.Type.External(it.external.name)

                        ModuleDevirtualizationAnalysisResult.Type.TypeCase.PUBLIC ->
                            FunctionTemplateBody.Type.Public(it.public.name, it.public.intestines.isFinal, it.public.intestines.isAbstract).also {
                                publicTypesMap.put(it.name, it)
                            }

                        ModuleDevirtualizationAnalysisResult.Type.TypeCase.PRIVATE ->
                            FunctionTemplateBody.Type.Private(it.private.name, privateTypeIndex++, it.private.intestines.isFinal, it.private.intestines.isAbstract)

                        else -> error("Unknown type: ${it.typeCase}")
                    }
                }

                val functionIds = symbolTable.functionIdsList.map {
                    when (it.functionIdCase) {
                        ModuleDevirtualizationAnalysisResult.FunctionId.FunctionIdCase.EXTERNAL ->
                            FunctionTemplateBody.FunctionId.External(it.external.name)

                        ModuleDevirtualizationAnalysisResult.FunctionId.FunctionIdCase.PUBLIC -> {
                            val symbolTableIndex = it.public.index
                            if (symbolTableIndex >= 0)
                                ++module.numberOfFunctions
                            FunctionTemplateBody.FunctionId.Public(it.public.name, module, symbolTableIndex).also {
                                publicFunctionsMap.put(it.name, it)
                            }
                        }

                        ModuleDevirtualizationAnalysisResult.FunctionId.FunctionIdCase.PRIVATE -> {
                            val symbolTableIndex = it.private.index
                            if (symbolTableIndex >= 0)
                                ++module.numberOfFunctions
                            FunctionTemplateBody.FunctionId.Private(it.private.name, privateFunIndex++, module, symbolTableIndex)
                        }

                        else -> error("Unknown function id: ${it.functionIdCase}")
                    }
                }
                println("Lib: ${library.libraryName}, types: ${types.size}, functions: ${functionIds.size}")
                symbolTable.typesList.forEachIndexed { index, type ->
                    val deserializedType = types[index] as? FunctionTemplateBody.Type.Declared
                            ?: return@forEachIndexed
                    val intestines = if (deserializedType is FunctionTemplateBody.Type.Public)
                                         type.public.intestines
                                     else
                                         type.private.intestines
                    deserializedType.superTypes += intestines.superTypesList.map { types[it] }
                    deserializedType.vtable += intestines.vtableList.map { functionIds[it] }
                    intestines.itableList.forEach {
                        deserializedType.itable.put(it.hash, functionIds[it.impl])
                    }
//                    println(deserializedType)
//                    println("ITABLE SIZE = ${intestines.itableList.size}")
//                    if (deserializedType is FunctionTemplateBody.Type.Private && deserializedType.name == "kotlin.IteratorImp") {
//                        println("ITABLE SIZE = ${intestines.itableList.size}")
//                    }
                }
//
//                println("TYPES TABLE")
//                types.forEach {
//                    println(it)
//                    if (it is FunctionTemplateBody.Type.Declared) {
//                        println("    Super types:")
//                        it.superTypes.forEach {
//                            println("        $it")
//                        }
//                    }
//                }

                fun deserializeEdge(edge: ModuleDevirtualizationAnalysisResult.Edge): FunctionTemplateBody.Edge {
                    return FunctionTemplateBody.Edge(if (edge.hasCastToType()) types[edge.castToType] else null)
                }

                fun deserializeCall(call: ModuleDevirtualizationAnalysisResult.Call): FunctionTemplateBody.Node.Call {
                    return FunctionTemplateBody.Node.Call(
                            functionIds[call.callee],
                            call.argumentsList.map { deserializeEdge(it) },
                            types[call.returnType]
                    )
                }

                fun deserializeVirtualCall(virtualCall: ModuleDevirtualizationAnalysisResult.VirtualCall): FunctionTemplateBody.Node.VirtualCall {
                    val call = deserializeCall(virtualCall.call)
                    return FunctionTemplateBody.Node.VirtualCall(
                            call.callee,
                            call.arguments,
                            call.returnType,
                            types[virtualCall.receiverType],
                            null
                    )
                }

                fun deserializeField(field: ModuleDevirtualizationAnalysisResult.Field): FunctionTemplateBody.Field {
                    val type = if (field.hasType()) types[field.type] else null
                    return FunctionTemplateBody.Field(type, field.name)
                }

                fun deserializeBody(body: ModuleDevirtualizationAnalysisResult.FunctionTemplateBody): FunctionTemplateBody {
                    val nodes = body.nodesList.map {
                        when (it.nodeCase) {
                            ModuleDevirtualizationAnalysisResult.Node.NodeCase.PARAMETER ->
                                    FunctionTemplateBody.Node.Parameter(it.parameter.index)

                            ModuleDevirtualizationAnalysisResult.Node.NodeCase.CONST ->
                                    FunctionTemplateBody.Node.Const(types[it.const.type])

                            ModuleDevirtualizationAnalysisResult.Node.NodeCase.STATICCALL -> {
                                val call = deserializeCall(it.staticCall.call)
                                val receiverType = if (it.staticCall.hasReceiverType()) types[it.staticCall.receiverType] else null
                                FunctionTemplateBody.Node.StaticCall(call.callee, call.arguments, call.returnType, receiverType)
                            }

                            ModuleDevirtualizationAnalysisResult.Node.NodeCase.NEWOBJECT -> {
                                val call = deserializeCall(it.newObject.call)
                                FunctionTemplateBody.Node.NewObject(call.callee, call.arguments, call.returnType)
                            }

                            ModuleDevirtualizationAnalysisResult.Node.NodeCase.VTABLECALL -> {
                                val virtualCall = deserializeVirtualCall(it.vtableCall.virtualCall)
                                FunctionTemplateBody.Node.VtableCall(
                                        virtualCall.callee,
                                        virtualCall.receiverType,
                                        it.vtableCall.calleeVtableIndex,
                                        virtualCall.arguments,
                                        virtualCall.returnType,
                                        virtualCall.callSite
                                )
                            }

                            ModuleDevirtualizationAnalysisResult.Node.NodeCase.ITABLECALL -> {
                                val virtualCall = deserializeVirtualCall(it.itableCall.virtualCall)
                                FunctionTemplateBody.Node.ItableCall(
                                        virtualCall.callee,
                                        virtualCall.receiverType,
                                        it.itableCall.calleeHash,
                                        virtualCall.arguments,
                                        virtualCall.returnType,
                                        virtualCall.callSite
                                )
                            }

                            ModuleDevirtualizationAnalysisResult.Node.NodeCase.SINGLETON -> {
                                FunctionTemplateBody.Node.Singleton(types[it.singleton.type])
                            }

                            ModuleDevirtualizationAnalysisResult.Node.NodeCase.FIELDREAD -> {
                                val fieldRead = it.fieldRead
                                val receiver = if (fieldRead.hasReceiver()) deserializeEdge(fieldRead.receiver) else null
                                FunctionTemplateBody.Node.FieldRead(receiver, deserializeField(fieldRead.field))
                            }

                            ModuleDevirtualizationAnalysisResult.Node.NodeCase.FIELDWRITE -> {
                                val fieldWrite = it.fieldWrite
                                val receiver = if (fieldWrite.hasReceiver()) deserializeEdge(fieldWrite.receiver) else null
                                FunctionTemplateBody.Node.FieldWrite(receiver, deserializeField(fieldWrite.field), deserializeEdge(fieldWrite.value))
                            }

                            ModuleDevirtualizationAnalysisResult.Node.NodeCase.VARIABLE -> {
                                FunctionTemplateBody.Node.Variable(it.variable.valuesList.map { deserializeEdge(it) })
                            }

                            ModuleDevirtualizationAnalysisResult.Node.NodeCase.TEMPVARIABLE -> {
                                FunctionTemplateBody.Node.TempVariable(it.tempVariable.valuesList.map { deserializeEdge(it) })
                            }

                            else -> error("Unknown node: ${it.nodeCase}")
                        }
                    }

                    body.nodesList.forEachIndexed { index, node ->
                        val deserializedNode = nodes[index]
                        when (node.nodeCase) {
                            ModuleDevirtualizationAnalysisResult.Node.NodeCase.STATICCALL -> {
                                node.staticCall.call.argumentsList.forEachIndexed { i, arg ->
                                    (deserializedNode as FunctionTemplateBody.Node.StaticCall).arguments[i].node = nodes[arg.node]
                                }
                            }

                            ModuleDevirtualizationAnalysisResult.Node.NodeCase.NEWOBJECT -> {
                                node.newObject.call.argumentsList.forEachIndexed { i, arg ->
                                    (deserializedNode as FunctionTemplateBody.Node.NewObject).arguments[i].node = nodes[arg.node]
                                }
                            }

                            ModuleDevirtualizationAnalysisResult.Node.NodeCase.VTABLECALL -> {
                                node.vtableCall.virtualCall.call.argumentsList.forEachIndexed { i, arg ->
                                    (deserializedNode as FunctionTemplateBody.Node.VtableCall).arguments[i].node = nodes[arg.node]
                                }
                            }

                            ModuleDevirtualizationAnalysisResult.Node.NodeCase.ITABLECALL -> {
                                node.itableCall.virtualCall.call.argumentsList.forEachIndexed { i, arg ->
                                    (deserializedNode as FunctionTemplateBody.Node.ItableCall).arguments[i].node = nodes[arg.node]
                                }
                            }

                            ModuleDevirtualizationAnalysisResult.Node.NodeCase.FIELDREAD -> {
                                val fieldRead = node.fieldRead
                                if (fieldRead.hasReceiver())
                                    (deserializedNode as FunctionTemplateBody.Node.FieldRead).receiver!!.node = nodes[fieldRead.receiver.node]
                            }

                            ModuleDevirtualizationAnalysisResult.Node.NodeCase.FIELDWRITE -> {
                                val deserializedFieldWrite = deserializedNode as FunctionTemplateBody.Node.FieldWrite
                                val fieldWrite = node.fieldWrite
                                if (fieldWrite.hasReceiver())
                                    deserializedFieldWrite.receiver!!.node = nodes[fieldWrite.receiver.node]
                                deserializedFieldWrite.value.node = nodes[fieldWrite.value.node]
                            }

                            ModuleDevirtualizationAnalysisResult.Node.NodeCase.VARIABLE -> {
                                node.variable.valuesList.forEachIndexed { i, value ->
                                    (deserializedNode as FunctionTemplateBody.Node.Variable).values[i].node = nodes[value.node]
                                }
                            }

                            ModuleDevirtualizationAnalysisResult.Node.NodeCase.TEMPVARIABLE -> {
                                node.tempVariable.valuesList.forEachIndexed { i, value ->
                                    (deserializedNode as FunctionTemplateBody.Node.TempVariable).values[i].node = nodes[value.node]
                                }
                            }

                            else -> {}
                        }
                    }
                    return FunctionTemplateBody(nodes, nodes[body.returns] as FunctionTemplateBody.Node.TempVariable)
                }

                moduleDevirtualizationAnalysisResult.functionTemplatesList.forEach {
                    val id = functionIds[it.id]
                    functionTemplates.put(id, FunctionTemplate(id, it.numberOfParameters, deserializeBody(it.body)))
                }
            }
        }
        intraproceduralAnalysisResult.symbolTable.privateTypeIndex = privateTypeIndex
        intraproceduralAnalysisResult.symbolTable.privateFunIndex = privateFunIndex

        val externalAnalysisResult = ExternalAnalysisResult(publicTypesMap, publicFunctionsMap, functionTemplates)
        val privateFunctions = intraproceduralAnalysisResult.symbolTable.functionMap
                .asSequence()
                .filter { it.key is FunctionDescriptor }
                .filter { it.value.let { it is FunctionTemplateBody.FunctionId.Declared && it.symbolTableIndex >= 0 } }
                .sortedBy { (it.value as FunctionTemplateBody.FunctionId.Declared).symbolTableIndex }
                .map { it.key as FunctionDescriptor }
                .toList()
        val devirtualizedCallSites = InterproceduralAnalysis(context, externalAnalysisResult, intraproceduralAnalysisResult).analyze(irModule)
        return DevirtualizationAnalysisResult(privateFunctions, devirtualizedCallSites)
    }

    internal fun devirtualize(irModule: IrModuleFragment, context: Context,
                              devirtualizedCallSites: Map<IrCall, DevirtualizedCallSite>) {
        irModule.transformChildrenVoid(object: IrElementTransformerVoidWithContext() {
            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)

                val devirtualizedCallSite = devirtualizedCallSites[expression]
                val actualCallee = devirtualizedCallSite?.possibleCallees?.singleOrNull()?.callee as? FunctionTemplateBody.FunctionId.Private
                        ?: return expression
                val startOffset = expression.startOffset
                val endOffset = expression.endOffset
                val irBuilder = context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol, startOffset, endOffset)
                irBuilder.run {
                    val dispatchReceiver = expression.dispatchReceiver!!
                    return IrPrivateFunctionCallImpl(
                            startOffset,
                            endOffset,
                            expression.type,
                            expression.symbol,
                            expression.descriptor,
                            (expression as? IrCallImpl)?.typeArguments,
                            actualCallee.module.name,
                            actualCallee.module.numberOfFunctions,
                            actualCallee.symbolTableIndex
                    ).apply {
                        this.dispatchReceiver    = dispatchReceiver
                        this.extensionReceiver   = expression.extensionReceiver
                        expression.descriptor.valueParameters.forEach {
                            this.putValueArgument(it.index, expression.getValueArgument(it))
                        }
                    }
                }
            }
        })
    }
}