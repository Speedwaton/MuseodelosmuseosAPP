package cl.triskeledu.museo

import android.app.Application
import cl.triskeledu.museo.data.LineaCarrito
import cl.triskeledu.museo.data.MuseoDao
import cl.triskeledu.museo.data.Repositorio
import cl.triskeledu.museo.data.Solicitud
import cl.triskeledu.museo.data.Usuario
import cl.triskeledu.museo.data.remote.ApiRepositorio
import cl.triskeledu.museo.model.Pantalla
import cl.triskeledu.museo.model.Rol
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

// ================================================================================================
// Clase     : ViewModelTest
// Propósito : Prueba el ViewModel con el repositorio, la API y Room simulados (MockK).
//             viewModelScope usa Dispatchers.Main, que no existe en el PC: se reemplaza por uno de pruebas.
// ================================================================================================
@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTest {

    private val despachador = StandardTestDispatcher()
    private val api = mockk<ApiRepositorio>()
    private val dao = mockk<MuseoDao>(relaxed = true)    // relaxed: lo que no se programa responde vacío
    private val repo = mockk<Repositorio>()

    private val cliente = Usuario(3, "Cliente Demo", "cliente@museo.demo", "", Rol.CLIENTE, "🎨")

    @Before
    fun preparar() {
        Dispatchers.setMain(despachador)
        every { repo.api } returns api
        every { repo.dao } returns dao
        every { dao.usuario(3) } returns flowOf(cliente)
        every { dao.museos() } returns flowOf(emptyList())
        every { dao.productos() } returns flowOf(emptyList())
        coEvery { repo.sincronizar(any()) } returns Result.success(Unit)
        coEvery { repo.avisar(any(), any(), any(), any(), any(), any()) } returns 1
    }

    @After
    fun limpiar() = Dispatchers.resetMain()

    private fun crearViewModel() = ViewModel(mockk<Application>(relaxed = true), repo)

    // Inicia sesión como Cliente con el repositorio simulado.
    private suspend fun conSesion(vm: ViewModel) {
        coEvery { repo.iniciarSesion(any(), any(), any()) } returns Result.success(cliente)
        vm.cambiarCorreo("cliente@museo.demo")
        vm.cambiarClave("Cliente#2026")
        vm.iniciarSesion()
    }

    // Prueba    : login con campos vacíos
    // Verifica  : que muestre el error y no llame al servidor.
    @Test
    fun iniciarSesion_sinDatos_muestraError_sinLlamarAlServidor() = runTest(despachador) {
        val vm = crearViewModel()

        vm.iniciarSesion()

        vm.acceso.value.error shouldBe "Completa correo y contraseña."
        coVerify(exactly = 0) { repo.iniciarSesion(any(), any(), any()) }
    }

    // Prueba    : login correcto
    // Verifica  : que abra el Inicio y descargue los datos del backend.
    @Test
    fun iniciarSesion_correcto_abreInicio_ySincroniza() = runTest(despachador) {
        val vm = crearViewModel()

        conSesion(vm)
        advanceUntilIdle()                                  // Termina la corrutina del login

        vm.pila.value.last() shouldBe Pantalla.Inicio
        vm.cargando.value shouldBe false
        coVerify { repo.sincronizar(cliente) }
    }

    // Prueba    : login rechazado por ms-usuarios
    // Verifica  : que el mensaje del servidor quede en la pantalla de acceso.
    @Test
    fun iniciarSesion_rechazado_muestraElMensajeDelServidor() = runTest(despachador) {
        coEvery { repo.iniciarSesion(any(), any(), any()) } returns Result.failure(Exception("Esta cuenta es de rol Administrador."))
        val vm = crearViewModel()
        vm.cambiarCorreo("admin@museo.demo")
        vm.cambiarClave("Admin#2026")

        vm.iniciarSesion()
        advanceUntilIdle()

        vm.acceso.value.error shouldBe "Esta cuenta es de rol Administrador."
        vm.pila.value.last() shouldBe Pantalla.Bienvenida
    }

    // Prueba    : registro con contraseña débil
    // Verifica  : que la validación local la rechace antes de llamar a ms-usuarios.
    @Test
    fun registrar_claveDebil_noLlamaAlServidor() = runTest(despachador) {
        val vm = crearViewModel()
        vm.editarRegistro {
            it.copy(nombre = it.nombre.copy(valor = "Ana Prueba"), correo = it.correo.copy(valor = "ana@museo.demo"),
                clave = it.clave.copy(valor = "corta"), confirmar = it.confirmar.copy(valor = "corta"))
        }

        vm.registrar()
        advanceUntilIdle()

        vm.registro.value.clave.error shouldBe "Usa al menos 8 caracteres"
        coVerify(exactly = 0) { repo.registrar(any(), any(), any(), any()) }
    }

    // Prueba    : compra correcta
    // Verifica  : que guarde la solicitud del servidor, vacíe el carrito y abra la pantalla de solicitudes.
    @Test
    fun comprar_correcto_vaciaCarrito_yAbreSolicitudes() = runTest(despachador) {
        val linea = LineaCarrito(6, "Luxómetro para salas", 64_900, 2, 5, 250, true)
        val solicitud = Solicitud(id = 9, usuarioId = 3, tipo = "Compra", resumen = "2 × Luxómetro para salas", total = 162_600)
        coEvery { dao.carritoActual(3) } returns listOf(linea)
        coEvery { api.comprar(3, listOf(linea), any(), any()) } returns Result.success(solicitud)
        val vm = crearViewModel()
        conSesion(vm)
        advanceUntilIdle()

        vm.comprar()
        advanceUntilIdle()

        coVerify { dao.guardarSolicitudes(listOf(solicitud)) }
        coVerify { dao.vaciarCarrito(3) }
        vm.pila.value.last() shouldBe Pantalla.Solicitudes
    }

    // Prueba    : compra sin stock
    // Verifica  : que muestre el mensaje de ms-tienda y NO vacíe el carrito.
    @Test
    fun comprar_sinStock_avisa_yConservaElCarrito() = runTest(despachador) {
        val linea = LineaCarrito(8, "Papel tissue libre de ácido", 11_900, 1, 0, 500, true)
        coEvery { dao.carritoActual(3) } returns listOf(linea)
        coEvery { api.comprar(any(), any(), any(), any()) } returns Result.failure(Exception("«Papel tissue libre de ácido» ya no tiene stock suficiente."))
        val vm = crearViewModel()
        val avisos = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.mensajes.toList(avisos) }  // Escucha los mensajes
        conSesion(vm)
        advanceUntilIdle()

        vm.comprar()
        advanceUntilIdle()

        avisos shouldContain "«Papel tissue libre de ácido» ya no tiene stock suficiente."
        coVerify(exactly = 0) { dao.vaciarCarrito(any()) }
    }
}
