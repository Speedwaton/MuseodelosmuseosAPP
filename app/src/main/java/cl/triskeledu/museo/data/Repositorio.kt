package cl.triskeledu.museo.data

import android.content.Context
import cl.triskeledu.museo.data.remote.ApiRepositorio
import cl.triskeledu.museo.model.Rol
import cl.triskeledu.museo.util.notificar

// Intermediario entre el ViewModel y los datos.
// - api: los 3 microservicios del backend (la fuente de verdad).
// - dao: la base local Room, que guarda una copia para mostrarla en pantalla aunque se corte la conexión.
class Repositorio(private val context: Context, val api: ApiRepositorio = ApiRepositorio()) {
    val dao = BaseDatos.obtener(context).dao()

    // ms-usuarios revisa correo, contraseña, rol y si la cuenta está activa. La cuenta se guarda en la caché.
    suspend fun iniciarSesion(correo: String, clave: String, rol: Rol): Result<Usuario> =
        api.iniciarSesion(correo, clave, rol).onSuccess { dao.guardarUsuario(it) }

    // ms-usuarios crea la cuenta de Cliente (si el correo no existe).
    suspend fun registrar(nombre: String, correo: String, clave: String, avatar: String): Result<Usuario> =
        api.registrar(nombre, correo, clave, avatar).onSuccess { dao.guardarUsuario(it) }

    // Descarga del backend lo que ve el usuario según su rol y lo guarda en Room.
    // Si falla, las pantallas siguen mostrando lo último que se guardó.
    suspend fun sincronizar(u: Usuario): Result<Unit> = runCatching {
        dao.reemplazarMuseos(api.museos(soloPublicados = !u.rol.veNoPublicadas).getOrThrow())
        if (u.rol.veCatalogo) dao.reemplazarProductos(api.productos(soloActivos = !u.rol.gestiona).getOrThrow())
        if (u.rol.gestiona) dao.guardarUsuarios(api.usuarios().getOrThrow())   // Antes que las solicitudes: trae los nombres
        if (u.rol != Rol.INVESTIGADOR) {
            val delUsuario = if (u.rol.gestiona) null else u.id                  // El Administrador ve todas
            dao.reemplazarSolicitudes(delUsuario, api.solicitudes(delUsuario).getOrThrow())
        }
    }

    // Envía un aviso a un rol, a todos (rol = null) o a usuarios concretos.
    // Los avisos viven en el teléfono. Si el aviso es para el usuario conectado, también aparece como notificación.
    suspend fun avisar(rol: Rol?, titulo: String, mensaje: String, destino: String?, actual: Usuario?, ids: List<Long>? = null): Int {
        val destinatarios = ids ?: dao.idsActivos(rol)
        dao.insertarNotificaciones(destinatarios.map { Notificacion(usuarioId = it, titulo = titulo, mensaje = mensaje, destino = destino) })
        if (actual != null && actual.id in destinatarios && actual.notificaciones) context.notificar(titulo, mensaje, destino)
        return destinatarios.size
    }
}
