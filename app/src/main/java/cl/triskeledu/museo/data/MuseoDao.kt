package cl.triskeledu.museo.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import cl.triskeledu.museo.model.Rol
import kotlinx.coroutines.flow.Flow

// Todas las consultas a la base de datos local (Room).
// Usuarios, museos, productos y solicitudes son una copia de lo que hay en el backend.
// Favoritos, carrito, historial QR y avisos viven solo en el teléfono.
@Dao
interface MuseoDao {

    // --- Copia local (caché) de lo que responden los microservicios ---
    // Room guarda lo último que llegó del servidor: así las pantallas siguen mostrando datos aunque se corte la conexión.
    @Upsert suspend fun guardarMuseos(lista: List<Museo>)
    @Upsert suspend fun guardarProductos(lista: List<Producto>)
    @Upsert suspend fun guardarSolicitudes(lista: List<Solicitud>)
    @Upsert suspend fun guardarUsuario(usuario: Usuario)
    @Query("DELETE FROM museos") suspend fun borrarMuseos()
    @Query("DELETE FROM productos") suspend fun borrarProductos()
    @Query("DELETE FROM solicitudes WHERE (:u IS NULL OR usuarioId = :u)") suspend fun borrarSolicitudes(u: Long?)
    // Quita una cuenta vieja con el mismo correo pero otro id (el correo no se puede repetir).
    @Query("DELETE FROM usuarios WHERE correo = :correo AND id != :id") suspend fun borrarCorreoRepetido(correo: String, id: Long)

    // Reemplaza los museos de la caché por los del servidor.
    @Transaction suspend fun reemplazarMuseos(lista: List<Museo>) { borrarMuseos(); guardarMuseos(lista) }
    // Reemplaza los productos de la caché por los del servidor.
    @Transaction suspend fun reemplazarProductos(lista: List<Producto>) { borrarProductos(); guardarProductos(lista) }
    // Reemplaza las solicitudes del usuario (o todas, si u es null) por las del servidor.
    @Transaction suspend fun reemplazarSolicitudes(u: Long?, lista: List<Solicitud>) { borrarSolicitudes(u); guardarSolicitudes(lista) }
    // Guarda las cuentas que llegaron del servidor.
    @Transaction suspend fun guardarUsuarios(lista: List<Usuario>) = lista.forEach { borrarCorreoRepetido(it.correo, it.id); guardarUsuario(it) }

    // --- Usuarios ---
    @Query("SELECT * FROM usuarios WHERE correo = :correo") suspend fun usuarioPorCorreo(correo: String): Usuario?
    @Query("SELECT * FROM usuarios WHERE id = :id") fun usuario(id: Long): Flow<Usuario?>
    @Query("SELECT * FROM usuarios ORDER BY rol, nombre") fun usuarios(): Flow<List<Usuario>>
    @Query("SELECT id FROM usuarios WHERE activo = 1 AND (:rol IS NULL OR rol = :rol)") suspend fun idsActivos(rol: Rol?): List<Long>
    @Query("UPDATE usuarios SET activo = :activo WHERE id = :id") suspend fun cambiarActivo(id: Long, activo: Boolean)

    // --- Museos y favoritos ---
    @Query("SELECT * FROM museos ORDER BY pais, nombre") fun museos(): Flow<List<Museo>>
    @Query("SELECT * FROM museos WHERE id = :id") suspend fun museo(id: Long): Museo?
    @Query("SELECT * FROM museos WHERE codigo = :codigo") suspend fun museoPorCodigo(codigo: String): Museo?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun guardarFavorito(favorito: Favorito)
    @Query("DELETE FROM favoritos WHERE usuarioId = :u AND museoId = :m") suspend fun quitarFavorito(u: Long, m: Long)
    @Query("SELECT museoId FROM favoritos WHERE usuarioId = :u") fun favoritos(u: Long): Flow<List<Long>>

    // --- Catálogo y carrito ---
    @Query("SELECT * FROM productos ORDER BY categoria, nombre") fun productos(): Flow<List<Producto>>
    @Query("SELECT * FROM productos WHERE codigo = :codigo") suspend fun productoPorCodigo(codigo: String): Producto?
    @Upsert suspend fun guardarItem(item: ItemCarrito)
    @Query("DELETE FROM carrito WHERE usuarioId = :u AND productoId = :p") suspend fun quitarItem(u: Long, p: Long)
    @Query(
        "SELECT c.productoId, p.nombre, p.precio, c.cantidad, p.stock, p.pesoGramos, p.activo " +
            "FROM carrito c JOIN productos p ON p.id = c.productoId WHERE c.usuarioId = :u ORDER BY p.nombre"
    )
    fun carrito(u: Long): Flow<List<LineaCarrito>>

    @Query("SELECT c.productoId, p.nombre, p.precio, c.cantidad, p.stock, p.pesoGramos, p.activo " +
        "FROM carrito c JOIN productos p ON p.id = c.productoId WHERE c.usuarioId = :u")
    suspend fun carritoActual(u: Long): List<LineaCarrito>

    // La compra la hace ms-tienda (descuenta el stock y crea la solicitud); aquí solo se vacía el carrito local.
    @Query("DELETE FROM carrito WHERE usuarioId = :u") suspend fun vaciarCarrito(u: Long)

    // --- Solicitudes ---
    // LEFT JOIN: si la cuenta del cliente aún no está en la caché, se muestra "Cliente #id".
    @Query("SELECT s.*, COALESCE(u.nombre, 'Cliente #' || s.usuarioId) AS cliente FROM solicitudes s LEFT JOIN usuarios u ON u.id = s.usuarioId " +
        "WHERE (:u IS NULL OR s.usuarioId = :u) ORDER BY s.fecha DESC")
    fun solicitudes(u: Long?): Flow<List<SolicitudConCliente>>
    @Query("UPDATE solicitudes SET estado = :estado WHERE id = :id") suspend fun cambiarEstado(id: Long, estado: EstadoSolicitud)

    // --- Historial de códigos QR ---
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertar(lectura: LecturaQr): Long
    @Query("UPDATE lecturas_qr SET veces = veces + 1, fecha = :fecha WHERE usuarioId = :u AND codigo = :codigo")
    suspend fun repetirLectura(u: Long, codigo: String, fecha: Long): Int
    @Query("SELECT * FROM lecturas_qr WHERE usuarioId = :u ORDER BY fecha DESC") fun lecturas(u: Long): Flow<List<LecturaQr>>

    // Guarda una lectura QR. Si ya existía, solo suma una vez más. Devuelve true si es nueva.
    @Transaction
    suspend fun registrarLectura(lectura: LecturaQr): Boolean =
        if (repetirLectura(lectura.usuarioId, lectura.codigo, lectura.fecha) > 0) false
        else insertar(lectura).let { true }

    // --- Notificaciones ---
    @Insert suspend fun insertarNotificaciones(lista: List<Notificacion>)
    @Query("SELECT * FROM notificaciones WHERE usuarioId = :u ORDER BY fecha DESC, id DESC") fun notificaciones(u: Long): Flow<List<Notificacion>>
    @Query("UPDATE notificaciones SET leida = 1 WHERE usuarioId = :u AND (:id IS NULL OR id = :id)")
    suspend fun marcarLeida(u: Long, id: Long?)
}
