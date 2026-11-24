package cl.triskeledu.museo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cl.triskeledu.museo.model.Rol

// La base de datos local SQLite (museo.db): guarda la copia de lo que llega del backend y los datos propios del teléfono.
// exportSchema guarda en app/schemas la estructura de cada versión.
@Database(
    entities = [Usuario::class, Museo::class, Favorito::class, Producto::class, ItemCarrito::class,
        Solicitud::class, LecturaQr::class, Notificacion::class],
    version = 2,
    exportSchema = true,
)
abstract class BaseDatos : RoomDatabase() {
    abstract fun dao(): MuseoDao

    companion object {
        @Volatile private var instancia: BaseDatos? = null

        // Abre la base una sola vez y la reutiliza en toda la app.
        fun obtener(context: Context): BaseDatos = instancia ?: synchronized(this) {
            instancia ?: Room.databaseBuilder(context.applicationContext, BaseDatos::class.java, "museo.db")
                .addMigrations(MIGRACION_1_2)   // Actualiza la base sin borrar los datos
                .build().also { instancia = it }
        }
    }
}

// Migración de la versión 1 a la 2: agrega la columna de la foto de cámara sin borrar nada.
val MIGRACION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE museos ADD COLUMN fotoRuta TEXT")
    }
}

// Cuentas de demostración que existen en ms-usuarios (data.sql). Solo se usan para el botón
// "Usar cuenta de demostración"; los museos y productos ahora los entrega el backend.
object DatosDemo {
    data class Cuenta(val rol: Rol, val nombre: String, val correo: String, val clave: String, val avatar: String)

    val CUENTAS = listOf(
        Cuenta(Rol.ADMINISTRADOR, "Admin Demo", "admin@museo.demo", "Admin#2026", "🏛️"),
        Cuenta(Rol.INVESTIGADOR, "Investigadora Demo", "investigador@museo.demo", "Investiga#2026", "🔍"),
        Cuenta(Rol.CLIENTE, "Cliente Demo", "cliente@museo.demo", "Cliente#2026", "🎨"),
        Cuenta(Rol.CLIENTE, "Lucía Demo", "lucia@museo.demo", "Cliente#2026", "🏺"),
    )
}
