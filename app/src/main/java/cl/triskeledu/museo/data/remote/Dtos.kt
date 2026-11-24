package cl.triskeledu.museo.data.remote

import cl.triskeledu.museo.data.Categoria
import cl.triskeledu.museo.data.EstadoFicha
import cl.triskeledu.museo.data.EstadoSolicitud
import cl.triskeledu.museo.data.Usuario
import cl.triskeledu.museo.model.Destino
import cl.triskeledu.museo.model.Modalidad
import cl.triskeledu.museo.model.Rol

// Objetos que viajan como JSON entre la app y los microservicios.
// Museo, Producto y Solicitud se reciben directo en las clases de Room porque el JSON trae los mismos campos.

// ---------------------------------------------------------------- ms-usuarios
// El servidor nunca envía la contraseña.
data class UsuarioDto(
    val id: Long, val nombre: String, val correo: String, val rol: Rol, val avatar: String, val activo: Boolean,
    val notificaciones: Boolean, val textoGrande: Boolean, val idioma: String,
)

// En Room la contraseña queda vacía: la valida el servidor, no la app.
fun UsuarioDto.aUsuario() = Usuario(id, nombre, correo, claveHash = "", rol, avatar, activo, notificaciones, textoGrande, idioma)

data class LoginBody(val correo: String, val clave: String, val rol: Rol)
data class RegistroBody(val nombre: String, val correo: String, val clave: String, val avatar: String)
data class PerfilBody(val nombre: String, val avatar: String, val notificaciones: Boolean, val textoGrande: Boolean, val idioma: String)
data class ActivoBody(val activo: Boolean)

// ---------------------------------------------------------------- ms-museos
// rolAutor decide el estado: la ficha del Administrador se publica, la del Investigador queda en revisión.
data class MuseoBody(
    val nombre: String, val pais: String, val ciudad: String, val descripcion: String, val clasificacion: String,
    val proyecto: String, val latitud: Double, val longitud: Double, val urlVisita: String, val imagenUrl: String?,
    val codigo: String, val fotoRuta: String?, val autorId: Long, val rolAutor: Rol,
)
data class RevisionBody(val estado: EstadoFicha, val observacion: String?)

// ---------------------------------------------------------------- ms-tienda
data class ProductoBody(
    val nombre: String, val categoria: Categoria, val descripcion: String, val precio: Int, val stock: Int,
    val pesoGramos: Int, val activo: Boolean, val codigo: String,
)
data class ItemBody(val productoId: Long, val cantidad: Int)
// El servidor recalcula precios, envío y stock: la app solo dice qué compra y cómo se envía.
data class CompraBody(val usuarioId: Long, val items: List<ItemBody>, val destino: Destino, val modalidad: Modalidad)
data class InformacionBody(val usuarioId: Long, val productoId: Long, val mensaje: String)
data class EstadoBody(val estado: EstadoSolicitud)
