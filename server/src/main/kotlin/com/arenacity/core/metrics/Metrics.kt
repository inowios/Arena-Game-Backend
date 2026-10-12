package com.arenacity.core.metrics

import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.DoubleAdder
import java.util.concurrent.atomic.LongAdder

/**
 * A small Prometheus registry (text exposition 0.0.4): counters with labels, gauges read at scrape time and one latency
 * histogram per name. Enough for request rates/latency, scheduler health and business counters without extra dependencies.
 */
object Metrics {
    private val help = ConcurrentHashMap<String, Pair<String, String>>()   // name -> (type, help)
    private val counters = ConcurrentHashMap<String, ConcurrentHashMap<String, LongAdder>>()
    private val gauges = ConcurrentHashMap<String, () -> Double>()
    private val histograms = ConcurrentHashMap<String, ConcurrentHashMap<String, Histogram>>()
    private val started = System.currentTimeMillis()

    val BUCKETS = doubleArrayOf(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)

    class Histogram {
        val counts = AtomicLongArray(BUCKETS.size + 1)
        val sum = DoubleAdder()
        fun observe(v: Double) { val i = BUCKETS.indexOfFirst { v <= it }; counts.incrementAndGet(if (i < 0) BUCKETS.size else i); sum.add(v) }
    }

    private fun labels(pairs: Array<out Pair<String, String>>) =
        if (pairs.isEmpty()) "" else pairs.joinToString(",", "{", "}") { (k, v) -> "$k=\"${v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")}\"" }

    fun counter(name: String, help: String) { this.help.putIfAbsent(name, "counter" to help) }
    fun inc(name: String, vararg labels: Pair<String, String>, by: Long = 1) {
        counters.getOrPut(name) { ConcurrentHashMap() }.getOrPut(labels(labels)) { LongAdder() }.add(by)
    }
    fun count(name: String, vararg labels: Pair<String, String>): Long = counters[name]?.get(labels(labels))?.sum() ?: 0L

    fun gauge(name: String, help: String, read: () -> Double) { this.help[name] = "gauge" to help; gauges[name] = read }

    fun histogram(name: String, help: String) { this.help.putIfAbsent(name, "histogram" to help) }
    fun observe(name: String, seconds: Double, vararg labels: Pair<String, String>) {
        histograms.getOrPut(name) { ConcurrentHashMap() }.getOrPut(labels(labels)) { Histogram() }.observe(seconds)
    }

    private fun num(v: Double) = if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    fun render(): String = buildString {
        fun header(name: String, fallback: String) { val (type, h) = help[name] ?: (fallback to name); append("# HELP $name $h\n# TYPE $name $type\n") }
        for (name in counters.keys.sorted()) {
            header(name, "counter")
            counters.getValue(name).toSortedMap().forEach { (l, v) -> append(name).append(l).append(' ').append(v.sum()).append('\n') }
        }
        for (name in histograms.keys.sorted()) {
            header(name, "histogram")
            histograms.getValue(name).toSortedMap().forEach { (l, h) ->
                val inner = l.removePrefix("{").removeSuffix("}")
                fun withLe(le: String) = if (inner.isEmpty()) "{le=\"$le\"}" else "{$inner,le=\"$le\"}"
                var cumulative = 0L
                BUCKETS.forEachIndexed { i, b -> cumulative += h.counts.get(i); append("${name}_bucket${withLe(num(b))} $cumulative\n") }
                cumulative += h.counts.get(BUCKETS.size)
                append("${name}_bucket${withLe("+Inf")} $cumulative\n")
                append("${name}_sum$l ${h.sum.sum()}\n${name}_count$l $cumulative\n")
            }
        }
        for (name in gauges.keys.sorted()) {
            val v = runCatching { gauges.getValue(name)() }.getOrNull() ?: continue
            header(name, "gauge"); append("$name ${num(v)}\n")
        }
    }

    /** JVM and process gauges (registered once at startup). */
    fun registerJvm() {
        val rt = Runtime.getRuntime()
        gauge("process_uptime_seconds", "Seconds since the server started") { (System.currentTimeMillis() - started) / 1000.0 }
        gauge("jvm_memory_used_bytes", "Heap in use") { (rt.totalMemory() - rt.freeMemory()).toDouble() }
        gauge("jvm_memory_max_bytes", "Maximum heap") { rt.maxMemory().toDouble() }
        gauge("jvm_threads_live", "Live threads") { ManagementFactory.getThreadMXBean().threadCount.toDouble() }
        gauge("process_cpu_count", "Available processors") { rt.availableProcessors().toDouble() }
    }
}
