package cl.triskeledu.museo.data.remote

import cl.triskeledu.museo.data.EstadoFicha
import cl.triskeledu.museo.data.EstadoSolicitud
import cl.triskeledu.museo.data.LineaCarrito
import cl.triskeledu.museo.data.Museo
import cl.triskeledu.museo.data.Producto
import cl.triskeledu.museo.data.Solicitud
import cl.triskeledu.museo.data.Usuario
import cl.triskeledu.museo.model.Destino
import cl.triskeledu.museo.model.Modalidad
import cl.triskeledu.museo.model.Rol
import org.json.JSONObject
import retrofit2.HttpException
import java.io.IOException

// Mensaje cuando el teléfono no llega al backend (apagado, sin adb reverse o sin red).
const val SIN_CONEXION = "No se pudo conectar con el servidor. Revisa que el backend esté encendido."

// Todas las llamadas a los 3 microservicios. Cada función devuelve Result: éxito con el dato, o error con un mensaje claro.
// Las APIs llegan por el constructor (inyección de dependencias): la app usa las reales y las pruebas usan unas simuladas.
class ApiRepositorio(
    private val usuarios: UsuariosApi = RetrofitInstance.usuarios,
    private val museos: MuseosApi = RetrofitInstance.museos,
    private val tienda: TiendaApi = RetrofitInstance.tienda,
) {
    // ---------------------------------------------------------------- ms-usuarios
    suspend fun iniciarSesion(correo: String, clave: String, rol: Rol): Result<Usuario> =
        llamar { usuarios.login(LoginBody(correo.trim().lowercase(), clave, rol)).aUsuario() }

    suspend fun registrar(nombre: String, correo: String, clave: String, avatar: String): Result<Usuario> =
        llamar { usuarios.registrar(RegistroBody(nombre.trim(), correo.trim().lowercase(), clave, avatar)).aUsuario() }

    suspend fun usuarios(): Result<List<Usuario>> = llamar { usuarios.listar().map { it.aUsuario() } }

    suspend fun guardarPerfil(u: Usuario): Result<Usuario> =
        llamar { usuarios.actualizarPerfil(u.id, PerfilBody(u.nombre, u.avatar, u.notificaciones, u.textoGrande, u.idioma)).aUsuario() }

    suspend fun cambiarActivoUsuario(id: Long, activo: Boolean): Result<Usuario> =
        llamar { usuarios.cambiarActivo(id, ActivoBody(activo)).aUsuario() }

    // ---------------------------------------------------------------- ms-museos
    suspend fun museos(soloPublicados: Boolean): Result<List<Museo>> = llamar { museos.listar(soloPublicados) }

    suspend fun museoPorCodigo(codigo: String): Result<Museo> = llamar { museos.porCodigo(codigo) }

    // Crea la ficha si m.id es 0; si no, la actualiza.
    suspend fun guardarMuseo(m: Museo, autor: Usuario): Result<Museo> = llamar {
        val datos = MuseoBody(m.nombre, m.pais, m.ciudad, m.descripcion, m.clasificacion, m.proyecto, m.latitud, m.longitud,
            m.urlVisita, m.imagenUrl, m.codigo, m.fotoRuta, autor.id, autor.rol)
        if (m.id == 0L) museos.crear(datos) else museos.actualizar(m.id, datos)
    }

    suspend fun revisarMuseo(id: Long, estado: EstadoFicha, observacion: String?): Result<Museo> =
        llamar { museos.revisar(id, RevisionBody(estado, observacion?.ifBlank { null })) }

    // ---------------------------------------------------------------- ms-tienda
    suspend fun productos(soloActivos: Boolean): Result<List<Producto>> = llamar { tienda.productos(soloActivos) }

    suspend fun productoPorCodigo(codigo: String): Result<Producto> = llamar { tienda.productoPorCodigo(codigo) }

    // Crea el producto si p.id es 0; si no, lo actualiza.
    suspend fun guardarProducto(p: Producto): Result<Producto> = llamar {
        val datos = ProductoBody(p.nombre, p.categoria, p.descripcion, p.precio, p.stock, p.pesoGramos, p.activo, p.codigo)
        if (p.id == 0L) tienda.crearProducto(datos) else tienda.actualizarProducto(p.id, datos)
    }

    suspend fun cambiarActivoProducto(id: Long, activo: Boolean): Result<Producto> =
        llamar { tienda.cambiarActivoProducto(id, ActivoBody(activo)) }

    // usuarioId = null trae todas las solicitudes (Administrador).
    suspend fun solicitudes(usuarioId: Long?): Result<List<Solicitud>> = llamar { tienda.solicitudes(usuarioId) }

    suspend fun comprar(usuarioId: Long, lineas: List<LineaCarrito>, destino: Destino, modalidad: Modalidad): Result<Solicitud> =
        llamar { tienda.comprar(CompraBody(usuarioId, lineas.map { ItemBody(it.productoId, it.cantidad) }, destino, modalidad)) }

    suspend fun pedirInformacion(usuarioId: Long, productoId: Long, mensaje: String): Result<Solicitud> =
        llamar { tienda.pedirInformacion(InformacionBody(usuarioId, productoId, mensaje.trim())) }

    suspend fun cambiarEstadoSolicitud(id: Long, estado: EstadoSolicitud): Result<Solicitud> =
        llamar { tienda.cambiarEstado(id, EstadoBody(estado)) }
}

// Ejecuta una llamada y convierte los errores en mensajes para mostrar en pantalla.
// - Error HTTP (400, 401, 404, 409...): usa el texto "error" que manda el servidor.
// - Sin conexión (IOException): avisa que el backend no responde.
suspend fun <T> llamar(bloque: suspend () -> T): Result<T> = try {
    Result.success(bloque())
} catch (e: HttpException) {
    Result.failure(Exception(mensajeDe(e)))
} catch (e: IOException) {
    Result.failure(Exception(SIN_CONEXION))
}

// Lee {"error": "..."} del cuerpo de la respuesta de error.
fun mensajeDe(e: HttpException): String {
    val cuerpo = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
    val texto = runCatching { JSONObject(cuerpo.orEmpty()).optString("error") }.getOrNull()
    return texto?.takeIf { it.isNotBlank() } ?: "El servidor respondió con error ${e.code()}."
}
