package mcp

import com.sun.jdi.BooleanValue
import com.sun.jdi.ByteValue
import com.sun.jdi.CharValue
import com.sun.jdi.DoubleValue
import com.sun.jdi.Field
import com.sun.jdi.FloatValue
import com.sun.jdi.IntegerValue
import com.sun.jdi.LongValue
import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import com.sun.jdi.ShortValue
import com.sun.jdi.StringReference
import com.sun.jdi.ThreadReference
import com.sun.jdi.VirtualMachine
import java.lang.reflect.Method as ReflectMethod

/**
 * Fake JDI objects via dynamic proxies. Every JDI type used here is an interface,
 * so tests can drive inspect/mutate logic with zero hardware and zero debugger.
 * Unhandled members throw UnsupportedOperationException — loud, not silent.
 */
@Suppress("UNCHECKED_CAST")
fun <T : Any> jdiProxy(iface: Class<T>, handler: (m: ReflectMethod, args: Array<out Any>?) -> Any?): T =
    java.lang.reflect.Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
        if (method.declaringClass == Object::class.java) {
            when (method.name) {
                "toString" -> "Fake${iface.simpleName}"
                "hashCode" -> 42
                "equals" -> false
                else -> throw UnsupportedOperationException("Object.${method.name}")
            }
        } else handler(method, args)
    } as T

private fun <T : Any> primProxy(iface: Class<T>, v: Any): T = jdiProxy(iface) { m, _ ->
    when (m.name) {
        "value" -> v
        else -> throw UnsupportedOperationException("${iface.simpleName}.${m.name}")
    }
}

fun fakeBool(v: Boolean): BooleanValue = primProxy(BooleanValue::class.java, v)
fun fakeByte(v: Byte): ByteValue = primProxy(ByteValue::class.java, v)
fun fakeChar(v: Char): CharValue = primProxy(CharValue::class.java, v)
fun fakeShort(v: Short): ShortValue = primProxy(ShortValue::class.java, v)
fun fakeInt(v: Int): IntegerValue = primProxy(IntegerValue::class.java, v)
fun fakeLong(v: Long): LongValue = primProxy(LongValue::class.java, v)
fun fakeFloat(v: Float): FloatValue = primProxy(FloatValue::class.java, v)
fun fakeDouble(v: Double): DoubleValue = primProxy(DoubleValue::class.java, v)

fun fakeStringRef(v: String): StringReference = jdiProxy(StringReference::class.java) { m, _ ->
    when (m.name) {
        "value" -> v
        else -> throw UnsupportedOperationException("StringReference.${m.name}")
    }
}

fun fakeThread(name: String, failName: Boolean = false): ThreadReference =
    jdiProxy(ThreadReference::class.java) { m, _ ->
        when (m.name) {
            "name" -> if (failName) throw RuntimeException("gone") else name
            else -> throw UnsupportedOperationException("ThreadReference.${m.name}")
        }
    }

fun fakeField(
    name: String,
    typeName: String,
    static: Boolean = false,
    final: Boolean = false,
    private: Boolean = false,
): Field = jdiProxy(Field::class.java) { m, _ ->
    when (m.name) {
        "name" -> name
        "typeName" -> typeName
        "isStatic" -> static
        "isFinal" -> final
        "isPrivate" -> private
        else -> throw UnsupportedOperationException("Field.${m.name}")
    }
}

fun fakeRefType(
    name: String,
    fields: Map<String, Field> = emptyMap(),
    methods: Map<String, List<com.sun.jdi.Method>> = emptyMap(),
    instances: List<ObjectReference> = emptyList(),
    staticValues: Map<String, com.sun.jdi.Value?> = emptyMap(),
): ReferenceType = jdiProxy(ReferenceType::class.java) { m, a ->
    when (m.name) {
        "name" -> name
        "fieldByName" -> fields[a!![0] as String]
        "allFields" -> fields.values.toList()
        "methodsByName" -> methods[a!![0] as String] ?: emptyList<com.sun.jdi.Method>()
        "allMethods" -> methods.values.flatten()
        "instances" -> instances
        "getValue" -> staticValues[(a!![0] as Field).name()]
        else -> throw UnsupportedOperationException("ReferenceType.${m.name}")
    }
}

fun fakeMethod(
    name: String,
    argTypes: List<String>,
    returnType: String = "void",
    static: Boolean = false,
    varArgs: Boolean = false,
): com.sun.jdi.Method = jdiProxy(com.sun.jdi.Method::class.java) { m, _ ->
    when (m.name) {
        "name" -> name
        "argumentTypeNames" -> argTypes
        "returnTypeName" -> returnType
        "isStatic" -> static
        "isVarArgs" -> varArgs
        "isConstructor" -> false
        else -> throw UnsupportedOperationException("Method.${m.name}")
    }
}

/** Records setValue calls and invokeMethod calls; getValue/invoke results scripted. */
class FakeObject(
    val typeName: String,
    val id: Long,
    var getValueFn: (Field) -> com.sun.jdi.Value? = { null },
    var invokeFn: (com.sun.jdi.Method) -> com.sun.jdi.Value? = { null },
    val refType: ReferenceType? = null,
) {
    val sets = mutableListOf<Pair<String, com.sun.jdi.Value?>>()
    val invokes = mutableListOf<List<com.sun.jdi.Value?>>()
    val ref: ObjectReference = jdiProxy(ObjectReference::class.java) { m, a ->
        when (m.name) {
            "uniqueID" -> id
            "referenceType" -> refType ?: fakeRefType(typeName)
            "getValue" -> getValueFn(a!![0] as Field)
            "setValue" -> run {
                sets.add((a!![0] as Field).name() to a[1] as com.sun.jdi.Value?)
                null
            }
            "invokeMethod" -> run {
                @Suppress("UNCHECKED_CAST")
                invokes.add(a!![2] as List<com.sun.jdi.Value?>)
                invokeFn(a[1] as com.sun.jdi.Method)
            }
            else -> throw UnsupportedOperationException("ObjectReference.${m.name}")
        }
    }
}

/** ClassType double: ReferenceType behavior plus a recording static setValue. */
class FakeClass(
    name: String,
    fields: Map<String, Field> = emptyMap(),
    methods: Map<String, List<com.sun.jdi.Method>> = emptyMap(),
    staticValues: Map<String, com.sun.jdi.Value?> = emptyMap(),
) {
    val sets = mutableListOf<Pair<String, com.sun.jdi.Value?>>()
    val ref: com.sun.jdi.ClassType = jdiProxy(com.sun.jdi.ClassType::class.java) { m, a ->
        when (m.name) {
            "name" -> name
            "fieldByName" -> fields[a!![0] as String]
            "allFields" -> fields.values.toList()
            "methodsByName" -> methods[a!![0] as String] ?: emptyList<com.sun.jdi.Method>()
            "allMethods" -> methods.values.flatten()
            "instances" -> emptyList<ObjectReference>()
            "getValue" -> staticValues[(a!![0] as Field).name()]
            "setValue" -> run {
                sets.add((a!![0] as Field).name() to a[1] as com.sun.jdi.Value?)
                null
            }
            else -> throw UnsupportedOperationException("ClassType.${m.name}")
        }
    }
}

fun fakeVm(classes: Map<String, ReferenceType> = emptyMap()): VirtualMachine =
    jdiProxy(VirtualMachine::class.java) { m, a ->
        when (m.name) {
            "classesByName" -> listOfNotNull(classes[a!![0] as String])
            "mirrorOf" -> when (val v = a!![0]) {
                is String -> fakeStringRef(v)
                is Boolean -> fakeBool(v)
                is Byte -> fakeByte(v)
                is Char -> fakeChar(v)
                is Short -> fakeShort(v)
                is Int -> fakeInt(v)
                is Long -> fakeLong(v)
                is Float -> fakeFloat(v)
                is Double -> fakeDouble(v)
                else -> throw UnsupportedOperationException("mirrorOf($v)")
            }
            else -> throw UnsupportedOperationException("VirtualMachine.${m.name}")
        }
    }
