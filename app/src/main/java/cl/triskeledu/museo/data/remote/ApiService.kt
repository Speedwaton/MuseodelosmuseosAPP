package cl.triskeledu.museo.data.remote

import cl.triskeledu.museo.data.Museo
import cl.triskeledu.museo.data.Producto
import cl.triskeledu.museo.data.Solicitud
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

// Contratos de la API REST: una interfaz por microservicio. Retrofit crea el código que hace cada petición HTTP.
// Las rutas son relativas a la URL base de cada servicio (ver RetrofitInstance).

// ms-usuarios (puerto 8081)
interface UsuariosApi {
    @POST("api/usuarios/login") suspend fun login(@Body datos: LoginBody): UsuarioDto
    @POST("api/usuarios/registro") suspend fun registrar(@Body datos: RegistroBody): UsuarioDto
    @GET("api/usuarios") suspend fun listar(): List<UsuarioDto>
    @GET("api/usuarios/{id}") suspend fun obtener(@Path("id") id: Long): UsuarioDto
    @PUT("api/usuarios/{id}") suspend fun actualizarPerfil(@Path("id") id: Long, @Body datos: PerfilBody): UsuarioDto
    @PATCH("api/usuarios/{id}/activo") suspend fun cambiarActivo(@Path("id") id: Long, @Body datos: ActivoBody): UsuarioDto
}

// ms-museos (puerto 8082)
interface MuseosApi {
    @GET("api/museos") suspend fun listar(@Query("soloPublicados") soloPublicados: Boolean): List<Museo>
    @GET("api/museos/codigo/{codigo}") suspend fun porCodigo(@Path("codigo") codigo: String): Museo
    @POST("api/museos") suspend fun crear(@Body datos: MuseoBody): Museo
    @PUT("api/museos/{id}") suspend fun actualizar(@Path("id") id: Long, @Body datos: MuseoBody): Museo
    @PATCH("api/museos/{id}/revision") suspend fun revisar(@Path("id") id: Long, @Body datos: RevisionBody): Museo
}

// ms-tienda (puerto 8083)
interface TiendaApi {
    @GET("api/productos") suspend fun productos(@Query("soloActivos") soloActivos: Boolean): List<Producto>
    @GET("api/productos/codigo/{codigo}") suspend fun productoPorCodigo(@Path("codigo") codigo: String): Producto
    @POST("api/productos") suspend fun crearProducto(@Body datos: ProductoBody): Producto
    @PUT("api/productos/{id}") suspend fun actualizarProducto(@Path("id") id: Long, @Body datos: ProductoBody): Producto
    @PATCH("api/productos/{id}/activo") suspend fun cambiarActivoProducto(@Path("id") id: Long, @Body datos: ActivoBody): Producto
    @GET("api/solicitudes") suspend fun solicitudes(@Query("usuarioId") usuarioId: Long?): List<Solicitud>
    @POST("api/solicitudes/compra") suspend fun comprar(@Body datos: CompraBody): Solicitud
    @POST("api/solicitudes/informacion") suspend fun pedirInformacion(@Body datos: InformacionBody): Solicitud
    @PATCH("api/solicitudes/{id}/estado") suspend fun cambiarEstado(@Path("id") id: Long, @Body datos: EstadoBody): Solicitud
}
