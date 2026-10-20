package com.arenacity.arena

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The reading rules of the client's Json.cs over kotlinx JsonElement (numbers are doubles, a missing or differently typed
 * value gives the default), so the Arena data and the cases read the same way on the server as on the device.
 */
object Js {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    fun parse(text: String): JsonElement = json.parseToJsonElement(text)
    fun obj(e: JsonElement?): JsonObject = e as? JsonObject ?: throw IllegalArgumentException("json: object expected")
    fun arr(e: JsonElement?): List<JsonElement> = when (e) { null, is JsonNull -> emptyList(); is JsonArray -> e; else -> throw IllegalArgumentException("json: array expected") }
    fun get(d: JsonObject?, key: String): JsonElement? = d?.get(key)?.takeUnless { it is JsonNull }
    fun isStr(e: JsonElement?): Boolean = e is JsonPrimitive && e.isString
    fun isNum(e: JsonElement?): Boolean = e is JsonPrimitive && !e.isString && e.doubleOrNull != null
    fun isBool(e: JsonElement?): Boolean = e is JsonPrimitive && !e.isString && e.booleanOrNull != null
    fun num(e: JsonElement): Double = (e as JsonPrimitive).doubleOrNull!!
    fun str(d: JsonObject?, key: String, dflt: String? = null): String? { val e = get(d, key); return if (isStr(e)) (e as JsonPrimitive).content else dflt }
    fun int(d: JsonObject?, key: String, dflt: Int = 0): Int { val e = get(d, key); return if (isNum(e)) Math.round(num(e!!)).toInt() else dflt }
    fun bool(d: JsonObject?, key: String, dflt: Boolean = false): Boolean { val e = get(d, key); return if (isBool(e)) (e as JsonPrimitive).booleanOrNull!! else dflt }
    fun list(d: JsonObject?, key: String): List<JsonElement> = get(d, key) as? JsonArray ?: emptyList()
    fun ints(d: JsonObject?, key: String): IntArray? {
        val l = get(d, key) as? JsonArray ?: return null
        return IntArray(l.size) { i -> if (isNum(l[i])) Math.round(num(l[i])).toInt() else 0 }
    }
    fun strs(d: JsonObject?, key: String): List<String> {
        val l = get(d, key) as? JsonArray ?: return emptyList()
        return l.map { if (isStr(it)) (it as JsonPrimitive).content else "" }
    }
    /** `(int)(double)x` of the C#: a number, else an exception as the cast would throw. */
    fun toInt(e: JsonElement): Int = num(e).toInt()
}
