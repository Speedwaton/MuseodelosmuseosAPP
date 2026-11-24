package cl.triskeledu.museo.data.remote

import cl.triskeledu.museo.data.Categoria
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
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.ResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

// ================================================================================================
// Clase     : ApiRepositorioTest
// Propósito : Prueba la capa de red con las 3 APIs simuladas (MockK). No se conecta al backend.
// ================================================================================================
class ApiRepositorioTest {

    private val usuarios = mockk<UsuariosApi>()          // API falsa de ms-usuarios
    private val museos = mockk<MuseosApi>()              // API falsa de ms-museos
    private val tienda = mockk<TiendaApi>()              // API falsa de ms-tienda
    private val repo = ApiRepositorio(usuarios, museos, tienda)  // Repositorio real con las APIs falsas

    private val clienteDto = UsuarioDto(3, "Cliente Demo", "cliente@museo.demo", Rol.CLIENTE, "🎨", true, true, false, "es")

    // Error HTTP como el que responde Spring Boot: {"error": "..."}.
    private fun errorHttp(codigo: Int, mensaje: String) =
        HttpException(Response.error<Any>(codigo, ResponseBody.create(MediaType.parse("application/json"), """{"error":"$mensaje"}""")))

    // Prueba    : login correcto
    // Verifica  : que envíe el correo limpio (sin espacios, en minúsculas) y convierta la respuesta en Usuario.
    @Test
    fun iniciarSesion_correcto_devuelveElUsuario() = runTest {
        val enviado = slot<LoginBody>()
        coEvery { usuarios.login(capture(enviado)) } returns clienteDto           // Preparar

        val resultado = repo.iniciarSesion("  Cliente@Museo.demo ", "Cliente#2026", Rol.CLIENTE)  // Actuar

        enviado.captured.correo shouldBe "cliente@museo.demo"                     // Verificar
        resultado.getOrThrow().nombre shouldBe "Cliente Demo"
        resultado.getOrThrow().claveHash shouldBe ""                              // La contraseña no llega a la app
    }

    // Prueba    : login con contraseña incorrecta (401)
    // Verifica  : que el mensaje sea el texto "error" que respondió el servidor.
    @Test
    fun iniciarSesion_401_entregaElMensajeDelServidor() = runTest {
        coEvery { usuarios.login(any()) } throws errorHttp(401, "Correo o contraseña incorrectos.")

        val resultado = repo.iniciarSesion("cliente@museo.demo", "mala", Rol.CLIENTE)

        resultado.isFailure shouldBe true
        resultado.exceptionOrNull()?.message shouldBe "Correo o contraseña incorrectos."
    }

    // Prueba    : backend apagado
    // Verifica  : que un error de red no cierre la app y entregue un mensaje claro.
    @Test
    fun sinConexion_entregaMensajeDeServidorApagado() = runTest {
        coEvery { museos.listar(any()) } throws IOException("Failed to connect to localhost/127.0.0.1:8082")

        val resultado = repo.museos(soloPublicados = true)

        resultado.exceptionOrNull()?.message shouldBe SIN_CONEXION
    }

    // Prueba    : error sin JSON
    // Verifica  : que si el cuerpo no trae "error", se muestre el código HTTP.
    @Test
    fun errorSinCuerpo_muestraElCodigo() = runTest {
        coEvery { tienda.productos(any()) } throws HttpException(Response.error<Any>(500, ResponseBody.create(null, "")))

        repo.productos(soloActivos = true).exceptionOrNull()?.message shouldBe "El servidor respondió con error 500."
    }

    // Prueba    : compra
    // Verifica  : que mande cada producto con su cantidad, el destino y la modalidad (el servidor calcula los precios).
    @Test
    fun comprar_enviaLosItemsDelCarrito() = runTest {
        val enviado = slot<CompraBody>()
        coEvery { tienda.comprar(capture(enviado)) } returns Solicitud(id = 7, usuarioId = 3, tipo = "Compra", resumen = "2 × Luxómetro", total = 162_600)
        val lineas = listOf(
            LineaCarrito(6, "Luxómetro", 64_900, 2, 5, 250, true),
            LineaCarrito(5, "Guantes", 9_900, 1, 50, 400, true),
        )

        val s = repo.comprar(3, lineas, Destino.INTERNACIONAL, Modalidad.EXPRESS).getOrThrow()

        enviado.captured.items shouldBe listOf(ItemBody(6, 2), ItemBody(5, 1))
        enviado.captured.destino shouldBe Destino.INTERNACIONAL
        enviado.captured.modalidad shouldBe Modalidad.EXPRESS
        s.id shouldBe 7
    }

    // Prueba    : compra sin stock (409)
    // Verifica  : que llegue el mensaje del servidor para mostrarlo en pantalla.
    @Test
    fun comprar_sinStock_entregaElMensaje() = runTest {
        coEvery { tienda.comprar(any()) } throws errorHttp(409, "«Papel tissue libre de ácido» ya no tiene stock suficiente.")

        repo.comprar(3, listOf(LineaCarrito(8, "Papel", 11_900, 1, 0, 500, true)), Destino.NACIONAL, Modalidad.ESTANDAR)
            .exceptionOrNull()?.message shouldBe "«Papel tissue libre de ácido» ya no tiene stock suficiente."
    }

    // Prueba    : guardar ficha nueva y existente
    // Verifica  : que id = 0 use POST (crear) y un id existente use PUT (actualizar), enviando el rol del autor.
    @Test
    fun guardarMuseo_eligeCrearOActualizar() = runTest {
        val investigadora = Usuario(2, "Investigadora Demo", "investigador@museo.demo", "", Rol.INVESTIGADOR, "🔍")
        val nueva = Museo(0, "Museo de Valparaíso", "Chile", "Valparaíso", "Patrimonio del puerto.", "Historia",
            "Historia y Sociedad", -33.04, -71.61, "https://www.mhnv.gob.cl/", codigo = "MUS-013")
        val enviado = slot<MuseoBody>()
        coEvery { museos.crear(capture(enviado)) } returns nueva.copy(id = 13, estado = EstadoFicha.EN_REVISION)
        coEvery { museos.actualizar(13, any()) } returns nueva.copy(id = 13)

        repo.guardarMuseo(nueva, investigadora).getOrThrow().id shouldBe 13
        repo.guardarMuseo(nueva.copy(id = 13), investigadora)

        enviado.captured.rolAutor shouldBe Rol.INVESTIGADOR
        enviado.captured.autorId shouldBe 2
        coVerify(exactly = 1) { museos.crear(any()) }
        coVerify(exactly = 1) { museos.actualizar(13, any()) }
    }

    // Prueba    : guardar producto nuevo
    // Verifica  : que use POST y devuelva el id que asignó el servidor.
    @Test
    fun guardarProducto_nuevo_usaElIdDelServidor() = runTest {
        val p = Producto(0, "Higrómetro digital", Categoria.ARTICULO, "Mide la humedad de las salas.", 29_900, 8, 200, codigo = "PRD-010")
        coEvery { tienda.crearProducto(any()) } returns p.copy(id = 10)

        repo.guardarProducto(p).getOrThrow().id shouldBe 10
    }

    // Prueba    : cambiar estado de una solicitud
    // Verifica  : que se envíe el estado nuevo a ms-tienda.
    @Test
    fun cambiarEstadoSolicitud_enviaElEstado() = runTest {
        coEvery { tienda.cambiarEstado(2, EstadoBody(EstadoSolicitud.ENVIADO)) } returns
            Solicitud(id = 2, usuarioId = 3, tipo = "Compra", resumen = "x", estado = EstadoSolicitud.ENVIADO)

        repo.cambiarEstadoSolicitud(2, EstadoSolicitud.ENVIADO).getOrThrow().estado shouldBe EstadoSolicitud.ENVIADO
    }
}
