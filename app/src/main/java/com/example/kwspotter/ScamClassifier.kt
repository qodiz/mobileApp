package com.example.kwspotter

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Офлайн-классификатор: TF–IDF по словам и парам слов, затем логистическая регрессия.
 * Числа экспортируются из mlModel/models/scam_tfidf_logreg.joblib в mobile_model.json.
 *
 * Токены обязаны совпадать со sklearn: нижний регистр и шаблон [а-яё]+(?:-[а-яё]+)?.
 * Вес признака — сырое число повторов, умноженное на idf, затем одна L2-норма.
 */
class ScamClassifier private constructor(
    private val intercept: Double,
    private val thresholdAlert: Double,
    private val vocab: Map<String, Int>,
    private val idf: DoubleArray,
    private val coef: DoubleArray,
) {

    data class Result(val probability: Double, val alert: Boolean, val hangup: Boolean)

    fun classify(text: String): Result {
        val p = probability(text)
        val hit = p >= thresholdAlert
        return Result(probability = p, alert = hit, hangup = hit)
    }

    private fun probability(text: String): Double {
        val tokens = ArrayList<String>()
        for (match in TOKEN.findAll(text.lowercase(Locale.ROOT))) {
            tokens.add(match.value)
        }
        return sigmoid(intercept + score(tokens))
    }

    private fun score(tokens: List<String>): Double {
        val counts = HashMap<String, Int>(tokens.size * 2)
        fun add(gram: String) {
            counts[gram] = (counts[gram] ?: 0) + 1
        }
        for (token in tokens) add(token)
        for (i in 0 until tokens.size - 1) add(tokens[i] + " " + tokens[i + 1])

        var sum = 0.0
        var sumSq = 0.0
        for ((gram, tf) in counts) {
            val idx = vocab[gram] ?: continue
            if (idx >= idf.size || idx >= coef.size) continue
            val weight = tf.toDouble() * idf[idx]
            sum += weight * coef[idx]
            sumSq += weight * weight
        }
        return if (sumSq > 0.0) sum / sqrt(sumSq) else 0.0
    }

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

    companion object {
        private const val TAG = "ScamClassifier"
        private const val MODEL_ASSET = "scam_classifier/mobile_model.json"
        private val TOKEN = Regex("""[а-яё]+(?:-[а-яё]+)?""")

        @Volatile
        private var instance: ScamClassifier? = null

        fun get(context: Context): ScamClassifier? {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: load(context.applicationContext).also { instance = it }
            }
        }

        private fun load(context: Context): ScamClassifier? = try {
            val model = JSONObject(readAsset(context, MODEL_ASSET))
            ScamClassifier(
                intercept = model.getDouble("intercept"),
                thresholdAlert = model.getDouble("threshold_alert"),
                vocab = intMap(model.getJSONObject("vocabulary")),
                idf = doubles(model.getJSONArray("idf")),
                coef = doubles(model.getJSONArray("coef")),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load scam classifier", e)
            null
        }

        private fun readAsset(context: Context, path: String): String =
            context.assets.open(path).bufferedReader().use { it.readText() }

        private fun intMap(obj: JSONObject): Map<String, Int> {
            val map = HashMap<String, Int>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = obj.getInt(key)
            }
            return map
        }

        private fun doubles(arr: JSONArray): DoubleArray = DoubleArray(arr.length()) { arr.getDouble(it) }
    }
}
