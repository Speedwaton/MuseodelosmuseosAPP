package cl.triskeledu.museo.data.remote

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

// Crea una sola vez (object = singleton) un cliente Retrofit por microservicio.
object RetrofitInstance {
    // Con "adb reverse tcp:8081 tcp:8081" (y lo mismo para 8082 y 8083), localhost del teléfono llega al PC.
    // Para usar la red Wi-Fi, cambia localhost por la IP del PC, por ejemplo "http://192.168.1.50".
    // La URL base siempre debe terminar en "/".
    private const val HOST = "http://localhost"
    const val URL_USUARIOS = "$HOST:8081/"
    const val URL_MUSEOS = "$HOST:8082/"
    const val URL_TIENDA = "$HOST:8083/"

    // Espera como máximo 5 segundos para conectar: así, si el backend está apagado, la app avisa rápido.
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun crear(url: String): Retrofit = Retrofit.Builder()
        .baseUrl(url)
        .client(http)
        .addConverterFactory(GsonConverterFactory.create())  // Convierte JSON <-> clases Kotlin
        .build()

    val usuarios: UsuariosApi by lazy { crear(URL_USUARIOS).create(UsuariosApi::class.java) }
    val museos: MuseosApi by lazy { crear(URL_MUSEOS).create(MuseosApi::class.java) }
    val tienda: TiendaApi by lazy { crear(URL_TIENDA).create(TiendaApi::class.java) }
}
