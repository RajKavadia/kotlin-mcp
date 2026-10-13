package mcp

import com.sun.jdi.InvocationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import mcp.inspect.renderValue
import mcp.mutate.callMethod
import mcp.session.chooseLooperStop
import mcp.mutate.pickOverload
import mcp.mutate.renderAppException
import mcp.mutate.setField
import mcp.mutate.signatureOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class RenderValueTest {

    @Test
    fun `null renders null`() = assertEquals("null", renderValue(null))

    @Test
    fun `string renders quoted`() = assertEquals("\"hi\"", renderValue(fakeStringRef("hi")))

    @Test
    fun `primitives render raw`() {
        assertEquals("3", renderValue(fakeInt(3)))
        assertEquals("true", renderValue(fakeBool(true)))
        assertEquals("2.5", renderValue(fakeDouble(2.5)))
        assertEquals("9", renderValue(fakeLong(9L)))
    }

    @Test
    fun `object renders type and id`() {
        val o = FakeObject("com.Foo", 11)
        assertEquals("<com.Foo#11>", renderValue(o.ref))
    }
}

class OverloadTest {

    @Test
    fun `signature renders types and return`() {
        assertEquals(
            "load(int, java.lang.String) : void",
            signatureOf(fakeMethod("load", listOf("int", "java.lang.String"))),
        )
    }

    @Test
    fun `exact count wins`() {
        val m = pickOverload(
            listOf(fakeMethod("load", listOf("int")), fakeMethod("load", emptyList())),
            0,
        )
        assertEquals(0, m.argumentTypeNames().size)
    }

    @Test
    fun `no count match lists candidates`() {
        val err: String = try {
            pickOverload(listOf(fakeMethod("load", listOf("int"))), 0)
            fail<String>("expected")
            ""
        } catch (e: IllegalArgumentException) {
            e.message!!
        }
        assertTrue(err.contains("0 argument") && err.contains("load(int)"), err)
    }

    @Test
    fun `ambiguity lists every candidate`() {
        val err: String = try {
            pickOverload(
                listOf(
                    fakeMethod("load", listOf("int")),
                    fakeMethod("load", listOf("java.lang.String")),
                ),
                1,
            )
            fail<String>("expected")
            ""
        } catch (e: IllegalArgumentException) {
            e.message!!
        }
        assertTrue(err.contains("2 overloads") && err.contains("load(int)") && err.contains("load(java.lang.String)"), err)
    }

    @Test
    fun `varargs accepts extra args`() {
        val m = pickOverload(listOf(fakeMethod("fmt", listOf("java.lang.String"), varArgs = true)), 3)
        assertEquals("fmt", m.name())
    }
}

class SetFieldTest {

    @Test
    fun `unknown class is actionable`() {
        val err: String = try {
            setField(fakeVm(), "com.Nope", "f", JsonPrimitive(1), null)
            fail<String>("expected")
            ""
        } catch (e: IllegalArgumentException) {
            e.message!!
        }
        assertTrue(err.contains("not loaded"), err)
    }

    @Test
    fun `unknown field lists what JDI sees`() {
        val type = fakeRefType("C", mapOf("a" to fakeField("a", "int")))
        val err: String = try {
            setField(fakeVm(mapOf("C" to type)), "C", "zzz", JsonPrimitive(1), null)
            fail<String>("expected")
            ""
        } catch (e: IllegalArgumentException) {
            e.message!!
        }
        assertTrue(err.contains("not found") && err.contains("a"), err)
    }

    @Test
    fun `final field refused with setter hint`() {
        val type = fakeRefType("C", mapOf("v" to fakeField("v", "int", final = true, private = true)))
        val err: String = try {
            setField(fakeVm(mapOf("C" to type)), "C", "v", JsonPrimitive(1), null)
            fail<String>("expected")
            ""
        } catch (e: IllegalArgumentException) {
            e.message!!
        }
        assertTrue(err.contains("final") && err.contains("call_method"), err)
    }

    @Test
    fun `type mismatch is a bad-value error`() {
        val type = fakeRefType("C", mapOf("n" to fakeField("n", "int")))
        val obj = FakeObject("C", 1)
        val withInst = fakeRefType("C", mapOf("n" to fakeField("n", "int")), instances = listOf(obj.ref))
        val err: String = try {
            setField(fakeVm(mapOf("C" to withInst)), "C", "n", JsonPrimitive("abc"), null)
            fail<String>("expected")
            ""
        } catch (e: IllegalArgumentException) {
            e.message!!
        }
        assertTrue(err.contains("Bad `value`") && type.name().isNotEmpty(), err)
    }

    @Test
    fun `instance write records value and reads back`() {
        val obj = FakeObject("C", 1, getValueFn = { fakeInt(7) })
        val type = fakeRefType("C", mapOf("count" to fakeField("count", "int")), instances = listOf(obj.ref))
        val out = setField(fakeVm(mapOf("C" to type)), "C", "count", JsonPrimitive(7), null)
        assertEquals(1, obj.sets.size)
        assertEquals("count", obj.sets.single().first)
        assertEquals("count = 7 (read back after write)", out)
    }

    @Test
    fun `multi-instance forces choice then honors id`() {
        val o1 = FakeObject("C", 11, getValueFn = { fakeInt(1) })
        val o2 = FakeObject("C", 22, getValueFn = { fakeInt(2) })
        val type = fakeRefType(
            "C",
            mapOf("count" to fakeField("count", "int")),
            instances = listOf(o1.ref, o2.ref),
        )
        val vm = fakeVm(mapOf("C" to type))
        val err: String = try {
            setField(vm, "C", "count", JsonPrimitive(5), null)
            fail<String>("expected")
            ""
        } catch (e: IllegalArgumentException) {
            e.message!!
        }
        assertTrue(err.contains("2 live instances") && err.contains("11") && err.contains("22"), err)
        val out = setField(vm, "C", "count", JsonPrimitive(5), "22")
        assertEquals("count = 2 (read back after write)", out)
        assertEquals(1, o2.sets.size)
        assertEquals(0, o1.sets.size)
    }

    @Test
    fun `static write goes to the type`() {
        val cls = FakeClass(
            "C",
            mapOf("mode" to fakeField("mode", "java.lang.String", static = true)),
            staticValues = mapOf("mode" to fakeStringRef("dark")),
        )
        val out = setField(fakeVm(mapOf("C" to cls.ref)), "C", "mode", JsonPrimitive("dark"), null)
        assertEquals("mode", cls.sets.single().first)
        assertEquals("dark", (cls.sets.single().second as com.sun.jdi.StringReference).value())
        assertEquals("mode = \"dark\" (read back after write)", out)
    }
}

class CallMethodTest {

    private val thread = fakeThread("main")

    @Test
    fun `unknown class and method are actionable`() {
        var err = try {
            callMethod(fakeVm(), thread, "C", "m", emptyList(), null)
            fail<String>("expected")
            ""
        } catch (e: IllegalArgumentException) {
            e.message!!
        }
        assertTrue(err.contains("not loaded"), err)

        val type = fakeRefType("C", methods = mapOf("go" to listOf(fakeMethod("go", emptyList()))))
        err = try {
            callMethod(fakeVm(mapOf("C" to type)), thread, "C", "zzz", emptyList(), null)
            fail<String>("expected")
            ""
        } catch (e: IllegalArgumentException) {
            e.message!!
        }
        assertTrue(err.contains("not found") && err.contains("go"), err)
    }

    @Test
    fun `void instance method returns ok`() {
        val obj = FakeObject("C", 1, invokeFn = { null })
        val type = fakeRefType(
            "C",
            methods = mapOf("load" to listOf(fakeMethod("load", emptyList()))),
            instances = listOf(obj.ref),
        )
        assertEquals("ok (void)", callMethod(fakeVm(mapOf("C" to type)), thread, "C", "load", emptyList(), null))
    }

    @Test
    fun `int method returns rendered value and gets converted arg`() {
        val obj = FakeObject("C", 1, invokeFn = { fakeInt(3) })
        val type = fakeRefType(
            "C",
            methods = mapOf("add" to listOf(fakeMethod("add", listOf("int"), "int"))),
            instances = listOf(obj.ref),
        )
        val out = callMethod(fakeVm(mapOf("C" to type)), thread, "C", "add", listOf(JsonPrimitive(2)), null)
        assertEquals("returned 3", out)
        assertEquals(1, obj.invokes.single().size)
        assertEquals(2, (obj.invokes.single().single() as com.sun.jdi.IntegerValue).value())
    }

    @Test
    fun `app exception renders type and message`() {
        val msgField = fakeField("detailMessage", "java.lang.String")
        val exType = fakeRefType("java.lang.RuntimeException", mapOf("detailMessage" to msgField))
        val ex = FakeObject(
            "java.lang.RuntimeException", 9,
            getValueFn = { fakeStringRef("boom") },
            refType = exType,
        )
        val obj = FakeObject("C", 1, invokeFn = { throw InvocationException(ex.ref) })
        val type = fakeRefType(
            "C",
            methods = mapOf("load" to listOf(fakeMethod("load", emptyList()))),
            instances = listOf(obj.ref),
        )
        val err: String = try {
            callMethod(fakeVm(mapOf("C" to type)), thread, "C", "load", emptyList(), null)
            fail<String>("expected")
            ""
        } catch (e: IllegalStateException) {
            e.message!!
        }
        assertEquals("App threw java.lang.RuntimeException: boom", err)
    }

    @Test
    fun `renderAppException without message is just the type`() {
        val exType = fakeRefType("java.lang.IllegalStateException")
        val ex = FakeObject("java.lang.IllegalStateException", 9, refType = exType)
        assertEquals("java.lang.IllegalStateException", renderAppException(ex.ref))
    }

    @Test
    fun `bad argument names position and signature`() {
        val obj = FakeObject("C", 1)
        val type = fakeRefType(
            "C",
            methods = mapOf("add" to listOf(fakeMethod("add", listOf("int"), "int"))),
            instances = listOf(obj.ref),
        )
        val err: String = try {
            callMethod(fakeVm(mapOf("C" to type)), thread, "C", "add", listOf(JsonPrimitive("xx")), null)
            fail<String>("expected")
            ""
        } catch (e: IllegalArgumentException) {
            e.message!!
        }
        assertTrue(err.contains("argument 1") && err.contains("add(int)"), err)
    }
}

class EventSuspendTest {
    @Test
    fun `loopOnce is preferred over loop and next`() {
        val usable = setOf(
            "android.os.Looper" to "loop",
            "android.os.Looper" to "loopOnce",
            "android.os.MessageQueue" to "next",
        )
        assertEquals("android.os.Looper" to "loopOnce", chooseLooperStop { c, m -> c to m in usable })
    }

    @Test
    fun `loop is used when loopOnce is absent`() {
        val usable = setOf("android.os.MessageQueue" to "next", "android.os.Looper" to "loop")
        assertEquals("android.os.Looper" to "loop", chooseLooperStop { c, m -> c to m in usable })
    }

    @Test
    fun `no looper method means step fallback`() {
        assertEquals(null, chooseLooperStop { _, _ -> false })
    }
}
