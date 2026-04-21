package com.aiassistant.domain.tool

import android.util.Log
import com.aiassistant.domain.service.ToolManager
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

private const val TAG = "WeatherTool"

class WeatherTool @Inject constructor() {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val gson = com.google.gson.Gson()

    init {
        register()
    }

    fun register() {
        ToolManager.registerTool(
            ToolManager.ToolDefinition(
                name = "weather",
                description = "Get current weather information for a city. Returns temperature, humidity, wind speed, weather condition, and forecast.",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "city" to mapOf(
                            "type" to "string",
                            "description" to "The name of the city (e.g., 'London', 'Tokyo', 'New York')"
                        ),
                        "units" to mapOf(
                            "type" to "string",
                            "description" to "Temperature unit: 'celsius' (default) or 'fahrenheit'",
                            "enum" to listOf("celsius", "fahrenheit")
                        )
                    ),
                    "required" to listOf("city")
                ),
                executor = { arguments ->
                    runCatching {
                        val args = gson.fromJson(arguments, Map::class.java)
                        val city = args["city"] as? String ?: ""
                        val units = args["units"] as? String ?: "celsius"
                        fetchWeather(city, units)
                    }
                }
            )
        )
    }

    fun fetchWeather(city: String, units: String): String {
        return try {
            Log.d(TAG, "Fetching weather for city: $city, units: $units")
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=${java.net.URLEncoder.encode(city, "UTF-8")}&count=1"
            Log.d(TAG, "Geo URL: $geoUrl")
            val geoRequest = Request.Builder().url(geoUrl).build()
            val geoResponse = httpClient.newCall(geoRequest).execute()
            Log.d(TAG, "Geo response code: ${geoResponse.code}")
            val geoBody = geoResponse.body?.string() ?: "{}"
            Log.d(TAG, "Geo response: $geoBody")
            val geoJson = com.google.gson.JsonParser.parseString(geoBody)

            val results = geoJson.asJsonObject?.get("results")?.asJsonArray
            if (results == null || results.isEmpty()) {
                return "Error: City '$city' not found"
            }

            val firstResult = results[0].asJsonObject
            val lat = firstResult.get("latitude")?.asDouble
            val lon = firstResult.get("longitude")?.asDouble

            if (lat == null || lon == null) {
                return "Error: Could not get coordinates for '$city'"
            }

            val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_direction_10m,pressure_msl&daily=temperature_2m_max,temperature_2m_min&temperature_unit=$units&timezone=auto"
            Log.d(TAG, "Weather URL: $weatherUrl")
            val weatherRequest = Request.Builder().url(weatherUrl).build()
            val weatherResponse = httpClient.newCall(weatherRequest).execute()
            Log.d(TAG, "Weather response code: ${weatherResponse.code}")
            val weatherBody = weatherResponse.body?.string() ?: "{}"
            Log.d(TAG, "Weather response: $weatherBody")
            val weatherJson = com.google.gson.JsonParser.parseString(weatherBody)

            val current = weatherJson.asJsonObject?.get("current")?.asJsonObject
            val daily = weatherJson.asJsonObject?.get("daily")?.asJsonObject

            val temp = current?.get("temperature_2m")?.asDouble ?: 0.0
            val feelsLike = current?.get("apparent_temperature")?.asDouble ?: 0.0
            val humidity = current?.get("relative_humidity_2m")?.asInt ?: 0
            val windSpeed = current?.get("wind_speed_10m")?.asDouble ?: 0.0
            val windDir = current?.get("wind_direction_10m")?.asInt ?: 0
            val pressure = current?.get("pressure_msl")?.asDouble ?: 0.0
            val weatherCode = current?.get("weather_code")?.asInt ?: 0
            val condition = getWeatherCondition(weatherCode)

            val tempMax = daily?.get("temperature_2m_max")?.asJsonArray?.get(0)?.asDouble ?: 0.0
            val tempMin = daily?.get("temperature_2m_min")?.asJsonArray?.get(0)?.asDouble ?: 0.0

            val windDirection = getWindDirection(windDir)

            buildString {
                append("Weather in $city:\n")
                append("Condition: $condition\n")
                append("Temperature: ${String.format("%.1f", temp)}°$units (feels like ${String.format("%.1f", feelsLike)}°$units)\n")
                append("High: ${String.format("%.1f", tempMax)}° / Low: ${String.format("%.1f", tempMin)}°\n")
                append("Humidity: $humidity%\n")
                append("Wind: ${String.format("%.1f", windSpeed)} km/h $windDirection\n")
                append("Pressure: ${String.format("%.1f", pressure)} hPa\n")
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: e.javaClass.simpleName
            "Error fetching weather: $errorMsg"
        }
    }

    private fun getWeatherCondition(code: Int): String {
        return when (code) {
            0 -> "Clear sky"
            1 -> "Mainly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Foggy"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing drizzle"
            61, 63, 65 -> "Rain"
            66, 67 -> "Freezing rain"
            71, 73, 75 -> "Snow"
            77 -> "Snow grains"
            80, 81, 82 -> "Heavy rain"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorm"
            96, 99 -> "Thunderstorm with hail"
            else -> "Unknown ($code)"
        }
    }

    private fun getWindDirection(degrees: Int): String {
        return when (degrees) {
            in 0..22 -> "N"
            in 23..67 -> "NE"
            in 68..112 -> "E"
            in 113..157 -> "SE"
            in 158..202 -> "S"
            in 203..247 -> "SW"
            in 248..292 -> "W"
            in 293..337 -> "NW"
            else -> "N"
        }
    }

  
}
