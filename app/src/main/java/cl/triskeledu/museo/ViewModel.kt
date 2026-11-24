package cl.triskeledu.museo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cl.triskeledu.museo.data.Categoria
import cl.triskeledu.museo.data.EstadoFicha
import cl.triskeledu.museo.data.EstadoSolicitud
import cl.triskeledu.museo.data.Favorito
import cl.triskeledu.museo.data.ItemCarrito
import cl.triskeledu.museo.data.LecturaQr
import cl.triskeledu.museo.data.LineaCarrito
import cl.triskeledu.museo.data.Museo
import cl.triskeledu.museo.data.Notificacion
import cl.triskeledu.museo.data.Producto
import cl.triskeledu.museo.data.Repositorio
import cl.triskeledu.museo.data.Solicitud
import cl.triskeledu.museo.data.remote.SIN_CONEXION
import cl.triskeledu.museo.data.SolicitudConCliente
import cl.triskeledu.museo.data.Usuario
import cl.triskeledu.museo.model.CampoFormulario
import cl.triskeledu.museo.model.ContenidoQr
import cl.triskeledu.museo.model.Destino
import cl.triskeledu.museo.model.LectorQr
import cl.triskeledu.museo.model.Modalidad
import cl.triskeledu.museo.model.Pantalla
import cl.triskeledu.museo.model.Rol
import cl.triskeledu.museo.model.Validar
import cl.triskeledu.museo.model.calcularEnvio
import cl.triskeledu.museo.model.campo
import cl.triskeledu.museo.model.clp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Lo que se escribe en la pantalla de inicio de sesión.
data class AccesoUi(val rol: Rol = Rol.CLIENTE, val correo: CampoFormulario = CampoFormulario(),
                    val clave: CampoFormulario = CampoFormulario(), val error: String? = null)

// Lo que se escribe en la pantalla de registro.
data class RegistroUi(val nombre: CampoFormulario = CampoFormulario(), val correo: CampoFormulario = CampoFormulario(),
                      val clave: CampoFormulario = CampoFormulario(), val confirmar: CampoFormulario = CampoFormulario(),
                      val avatar: String = "🎨", val error: String? = null)

// Formulario para crear o editar una ficha de museo o un producto.
data class FormUi(val id: Long? = null, val campos: Map<String, CampoFormulario> = emptyMap(), val foto: String? = null,
                  val categoria: Categoria = Categoria.CURSO, val activo: Boolean = true, val error: String? = null) {
    fun v(etiqueta: String) = campos[etiqueta]?.valor?.trim().orEmpty()
}

// Lo que muestra la pantalla de resultado del QR: leyendo, encontrado o error.
sealed interface EstadoQr {
    data object Leyendo : EstadoQr
    data class Ok(val contenido: ContenidoQr, val titulo: String, val detalle: String, val imagen: String?,
                  val destino: Pantalla, val url: String, val nueva: Boolean) : EstadoQr
    data class Error(val mensaje: String, val texto: String, val contenido: ContenidoQr?) : EstadoQr
}

// Campos de cada formulario y la regla que valida cada uno.
val CAMPOS_FICHA: Map<String, (String) -> String?> = linkedMapOf(
    "Nombre" to { Validar.texto(it) }, "País" to { Validar.texto(it) }, "Ciudad" to { Validar.texto(it, 2) },
    "Clasificación" to { Validar.texto(it) }, "Proyecto" to { Validar.texto(it) }, "Descripción" to { Validar.texto(it, 10) },
    "Latitud" to { Validar.numero(it, -90.0..90.0) }, "Longitud" to { Validar.numero(it, -180.0..180.0) },
    "Enlace a la visita virtual" to Validar::url, "Foto web (URL, opcional)" to { if (it.isBlank()) null else Validar.url(it) },
    "Código QR" to Validar::codigo,
)
val CAMPOS_PRODUCTO: Map<String, (String) -> String?> = linkedMapOf(
    "Nombre" to { Validar.texto(it) }, "Descripción" to { Validar.texto(it, 10) }, "Precio (CLP)" to Validar::entero,
    "Stock / cupos" to Validar::entero, "Peso en gramos (0 = digital)" to Validar::entero, "Código QR" to Validar::codigo,
)

// ViewModel de la app (MVVM): guarda el estado de todas las pantallas.
// Las pantallas leen este estado y llaman a sus funciones; nunca tocan la API ni la base de datos.
// El repositorio llega por el constructor para que las pruebas puedan usar uno simulado.
// @JvmOverloads crea también el constructor (Application) que usa viewModel() en las pantallas.
@OptIn(ExperimentalCoroutinesApi::class)
class ViewModel @JvmOverloads constructor(app: Application, private val repo: Repositorio = Repositorio(app)) : AndroidViewModel(app) {
    // api: microservicios del backend. dao: copia local en Room.
    private val api = repo.api
    private val dao = repo.dao

    // ================= Sesión, navegación y mensajes
    // Usuario conectado (null si nadie inició sesión).
    private val idSesion = MutableStateFlow<Long?>(null)
    val usuario: StateFlow<Usuario?> = idSesion.flatMapLatest { id -> id?.let(dao::usuario) ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val yo get() = usuario.value

    // Pantallas abiertas: la última es la que se ve.
    val pila = MutableStateFlow<List<Pantalla>>(listOf(Pantalla.Bienvenida))
    private var pendiente: Pantalla? = null
    // Mensajes cortos que aparecen abajo de la pantalla.
    val mensajes = MutableSharedFlow<String>(extraBufferCapacity = 5)
    private fun aviso(texto: String) = mensajes.tryEmit(texto)

    // Abre una pantalla si el usuario tiene sesión y su rol lo permite.
    fun ir(p: Pantalla) {
        val u = yo
        when {
            p == Pantalla.Acceso || p == Pantalla.Registro -> pila.update { it + p }
            u == null -> { pendiente = p; pila.value = listOf(Pantalla.Bienvenida, Pantalla.Acceso); aviso("Inicia sesión para continuar.") }
            !p.permitida(u.rol) -> aviso("El rol ${u.rol.etiqueta} no tiene acceso a esta función.")
            else -> pila.update { it + p }
        }
    }
    // Cambia de pestaña en la barra inferior.
    // Al cambiar de pestaña se actualizan los datos desde el backend.
    fun pestana(p: Pantalla) { pila.value = if (p == Pantalla.Inicio) listOf(p) else listOf(Pantalla.Inicio, p); sincronizar() }
    // Cambia la pantalla actual por otra.
    fun reemplazar(p: Pantalla) = pila.update { it.dropLast(1) + p }
    // Vuelve a la pantalla anterior.
    fun volver(): Boolean = (pila.value.size > 1).also { if (it) pila.update { l -> l.dropLast(1) } }
    // Abre la pantalla de una notificación o del historial QR.
    fun abrirDestino(destino: String?) { Pantalla.desde(destino)?.let(::ir) }

    // Consulta solo los datos del usuario conectado, para que no se mezclen los perfiles.
    private fun <T> porUsuario(vacio: T, consulta: (Usuario) -> Flow<T>): StateFlow<T> =
        usuario.flatMapLatest { u -> u?.let(consulta) ?: flowOf(vacio) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), vacio)

    // ================= Inicio de sesión y registro
    val acceso = MutableStateFlow(AccesoUi())
    val registro = MutableStateFlow(RegistroUi())

    fun elegirRol(rol: Rol) = acceso.update { it.copy(rol = rol, error = null) }
    fun cambiarCorreo(v: String) = acceso.update { it.copy(correo = campo(v, Validar::correo), error = null) }
    fun cambiarClave(v: String) = acceso.update { it.copy(clave = campo(v) { c -> if (c.isBlank()) "Campo obligatorio" else null }, error = null) }
    // Rellena el correo y la contraseña de la cuenta de prueba del rol elegido.
    fun usarDemo() = acceso.update { a ->
        val c = cl.triskeledu.museo.data.DatosDemo.CUENTAS.first { it.rol == a.rol }
        a.copy(correo = CampoFormulario(c.correo), clave = CampoFormulario(c.clave), error = null)
    }

    // Revisa los datos y entra a la app.
    fun iniciarSesion() {
        val a = acceso.value
        if (!a.correo.esValido || a.correo.valor.isBlank() || a.clave.valor.isBlank()) {
            acceso.update { it.copy(error = "Completa correo y contraseña.") }; return
        }
        viewModelScope.launch {
            cargando.value = true
            repo.iniciarSesion(a.correo.valor, a.clave.valor, a.rol)
                .onSuccess { entrar(it); acceso.value = AccesoUi(rol = a.rol) }
                .onFailure { e -> acceso.update { it.copy(error = e.message) } }
            cargando.value = false
        }
    }

    fun editarRegistro(f: (RegistroUi) -> RegistroUi) = registro.update(f)

    // Valida el formulario y crea la cuenta de Cliente.
    fun registrar() {
        val r = registro.value
        val v = r.copy(
            nombre = campo(r.nombre.valor) { Validar.texto(it) }, correo = campo(r.correo.valor, Validar::correo),
            clave = campo(r.clave.valor, Validar::clave),
            confirmar = campo(r.confirmar.valor) { if (it == r.clave.valor && it.isNotEmpty()) null else "Las contraseñas no coinciden" },
        )
        registro.value = v
        if (listOf(v.nombre, v.correo, v.clave, v.confirmar).any { !it.esValido }) return
        viewModelScope.launch {
            repo.registrar(v.nombre.valor, v.correo.valor, v.clave.valor, v.avatar)
                .onSuccess { entrar(it); registro.value = RegistroUi(); aviso("Cuenta creada. ¡Bienvenido/a!") }
                .onFailure { e -> registro.update { it.copy(error = e.message) } }
        }
    }

    // Guarda la sesión, abre el inicio y descarga los datos del backend.
    private fun entrar(u: Usuario) {
        idSesion.value = u.id
        sesion = u
        pila.value = listOfNotNull(Pantalla.Inicio, pendiente?.takeIf { it.permitida(u.rol) })
        pendiente = null
        sincronizar()
    }

    // Usuario recién validado por el servidor: sirve mientras Room todavía no lo entrega por el Flow.
    private var sesion: Usuario? = null

    // true mientras se espera la respuesta del servidor al iniciar sesión o al comprar.
    val cargando = MutableStateFlow(false)

    // Trae del backend museos, productos, solicitudes y cuentas según el rol, y los guarda en Room.
    fun sincronizar() {
        val u = yo ?: sesion ?: return
        viewModelScope.launch {
            repo.sincronizar(u).onFailure { aviso("Sin conexión con el servidor: se muestran los datos guardados.") }
        }
    }

    // Sale de la cuenta y vuelve al acceso o a la bienvenida.
    fun cerrarSesion(cambiarUsuario: Boolean) {
        idSesion.value = null
        sesion = null
        pila.value = listOf(if (cambiarUsuario) Pantalla.Acceso else Pantalla.Bienvenida)
    }

    // ================= Museos, mapa y favoritos
    // Museos que puede ver el rol (el Cliente solo ve los publicados).
    val museos: StateFlow<List<Museo>> = combine(dao.museos(), usuario) { lista, u ->
        if (u?.rol?.veNoPublicadas == true) lista else lista.filter { it.estado == EstadoFicha.PUBLICADO }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Filtros del mapa y museo seleccionado.
    val filtroPais = MutableStateFlow<String?>(null)
    val filtroProyecto = MutableStateFlow<String?>(null)
    val seleccionado = MutableStateFlow<Long?>(null)
    val museosFiltrados = combine(museos, filtroPais, filtroProyecto) { l, pais, proyecto ->
        l.filter { (pais == null || it.pais == pais) && (proyecto == null || it.proyecto == proyecto) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Favoritos del usuario conectado.
    val favoritos: StateFlow<List<Long>> = porUsuario(emptyList()) { dao.favoritos(it.id) }

    // Guarda o quita un museo de favoritos.
    fun alternarFavorito(m: Museo) {
        val u = yo ?: return
        viewModelScope.launch {
            if (m.id in favoritos.value) { dao.quitarFavorito(u.id, m.id); aviso("Quitado de tu colección.") }
            else { dao.guardarFavorito(Favorito(u.id, m.id)); aviso("Guardado en favoritos.") }
        }
    }

    // El Administrador corrige cualquier ficha; el Investigador solo las suyas.
    fun puedeEditar(m: Museo) = yo?.let { it.rol == Rol.ADMINISTRADOR || (it.rol == Rol.INVESTIGADOR && m.autorId == it.id) } == true

    // El Administrador publica u observa una ficha y avisa al Investigador.
    fun revisar(m: Museo, estado: EstadoFicha, observacion: String?) {
        val u = yo?.takeIf { it.rol.gestiona } ?: return
        viewModelScope.launch {
            val revisada = api.revisarMuseo(m.id, estado, observacion).getOrElse { aviso(it.message ?: SIN_CONEXION); return@launch }
            dao.guardarMuseos(listOf(revisada))
            m.autorId?.let { repo.avisar(null, "Revisión de ficha", "«${m.nombre}»: ${estado.etiqueta}. ${observacion.orEmpty()}", "museo:${m.id}", u, listOf(it)) }
            if (estado == EstadoFicha.PUBLICADO) repo.avisar(Rol.CLIENTE, "Museo incorporado", "${m.nombre} ya está en el mapa.", "museo:${m.id}", u)
            aviso("Ficha marcada como ${estado.etiqueta}.")
        }
    }

    // ================= Formularios de ficha y de producto
    val form = MutableStateFlow(FormUi())

    // Llena el formulario con la ficha a editar, o lo deja vacío si es nueva.
    fun prepararFicha(id: Long?) = viewModelScope.launch {
        val m = id?.let { dao.museo(it) }
        val valores = listOf(m?.nombre, m?.pais, m?.ciudad, m?.clasificacion, m?.proyecto, m?.descripcion,
            m?.latitud?.toString(), m?.longitud?.toString(), m?.urlVisita, m?.imagenUrl, m?.codigo)
        form.value = FormUi(id, CAMPOS_FICHA.keys.zip(valores).associate { (k, v) -> k to CampoFormulario(v.orEmpty()) }, m?.fotoRuta)
    }

    // Llena el formulario con el producto a editar, o lo deja vacío si es nuevo.
    fun prepararProducto(id: Long?) = viewModelScope.launch {
        val p = productos.value.firstOrNull { it.id == id }
        val valores = listOf(p?.nombre, p?.descripcion, p?.precio?.toString(), p?.stock?.toString(), p?.pesoGramos?.toString(), p?.codigo)
        form.value = FormUi(id, CAMPOS_PRODUCTO.keys.zip(valores).associate { (k, v) -> k to CampoFormulario(v.orEmpty()) },
            categoria = p?.categoria ?: Categoria.CURSO, activo = p?.activo ?: true)
    }

    // Actualiza un campo y lo valida mientras se escribe.
    fun cambiarCampo(etiqueta: String, valor: String, reglas: Map<String, (String) -> String?>) = form.update {
        it.copy(campos = it.campos + (etiqueta to campo(valor, reglas.getValue(etiqueta))), error = null)
    }
    fun editarForm(f: (FormUi) -> FormUi) = form.update(f)

    // Valida todos los campos. Devuelve false si alguno tiene error.
    private fun validarForm(reglas: Map<String, (String) -> String?>): Boolean {
        form.update { f -> f.copy(campos = reglas.mapValues { (k, r) -> campo(f.v(k), r) }) }
        return form.value.campos.values.all { it.esValido }.also { if (!it) form.update { f -> f.copy(error = "Revisa los campos marcados.") } }
    }

    // Guarda la ficha. Si la crea el Investigador, queda en revisión y se avisa al Administrador.
    fun guardarFicha() {
        val u = yo?.takeIf { it.rol.editaFichas } ?: return
        if (!validarForm(CAMPOS_FICHA)) return
        val f = form.value
        viewModelScope.launch {
            val anterior = f.id?.let { dao.museo(it) }
            val otro = dao.museoPorCodigo(f.v("Código QR").uppercase())
            if (otro != null && otro.id != f.id) { form.update { it.copy(error = "Ese código ya es de «${otro.nombre}».") }; return@launch }
            if (anterior != null && !puedeEditar(anterior)) { form.update { it.copy(error = "Solo puedes corregir tus fichas.") }; return@launch }
            val m = Museo(
                id = f.id ?: 0, nombre = f.v("Nombre"), pais = f.v("País"), ciudad = f.v("Ciudad"),
                descripcion = f.v("Descripción"), clasificacion = f.v("Clasificación"), proyecto = f.v("Proyecto"),
                latitud = f.v("Latitud").replace(',', '.').toDouble(), longitud = f.v("Longitud").replace(',', '.').toDouble(),
                urlVisita = f.v("Enlace a la visita virtual"), imagenUrl = f.v("Foto web (URL, opcional)").ifBlank { null },
                codigo = f.v("Código QR").uppercase(), autorId = anterior?.autorId ?: u.id, fotoRuta = f.foto,
            )
            // ms-museos guarda la ficha y decide su estado según el rol (la del Investigador queda en revisión).
            val guardado = api.guardarMuseo(m, u).getOrElse { e -> form.update { it.copy(error = e.message) }; return@launch }
            dao.guardarMuseos(listOf(guardado))
            val id = guardado.id
            if (guardado.estado == EstadoFicha.EN_REVISION) repo.avisar(Rol.ADMINISTRADOR, "Ficha por revisar", "${u.nombre} envió «${m.nombre}».", "museo:$id", u)
            aviso("Ficha guardada.")
            reemplazar(Pantalla.Museo(id))
        }
    }

    // Guarda el producto. Si es nuevo, avisa a los clientes.
    fun guardarProducto() {
        val u = yo?.takeIf { it.rol.gestiona } ?: return
        if (!validarForm(CAMPOS_PRODUCTO)) return
        val f = form.value
        viewModelScope.launch {
            val otro = dao.productoPorCodigo(f.v("Código QR").uppercase())
            if (otro != null && otro.id != f.id) { form.update { it.copy(error = "Ese código ya es de «${otro.nombre}».") }; return@launch }
            val p = Producto(f.id ?: 0, f.v("Nombre"), f.categoria, f.v("Descripción"), f.v("Precio (CLP)").toInt(),
                f.v("Stock / cupos").toInt(), f.v("Peso en gramos (0 = digital)").toInt(), f.activo, f.v("Código QR").uppercase())
            // ms-tienda guarda el producto y responde con su id definitivo.
            val guardado = api.guardarProducto(p).getOrElse { e -> form.update { it.copy(error = e.message) }; return@launch }
            dao.guardarProductos(listOf(guardado))
            if (f.id == null) repo.avisar(Rol.CLIENTE, if (p.esCurso) "Nuevo curso" else "Nuevo producto", "Ya está disponible «${p.nombre}».", "producto:${guardado.id}", u)
            aviso("Producto guardado.")
            volver()
        }
    }

    // ================= Catálogo, carrito y solicitudes
    // Productos que puede ver el rol (el Cliente no ve los desactivados).
    val productos: StateFlow<List<Producto>> = combine(dao.productos(), usuario) { l, u -> if (u?.rol?.gestiona == true) l else l.filter { it.activo } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    // Búsqueda y categoría elegidas en el catálogo.
    val busqueda = MutableStateFlow("")
    val categoria = MutableStateFlow<Categoria?>(null)
    val catalogo = combine(productos, busqueda, categoria) { l, texto, cat ->
        l.filter { (cat == null || it.categoria == cat) && it.nombre.contains(texto.trim(), ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Activa o desactiva un producto (Administrador).
    fun alternarActivo(p: Producto) = viewModelScope.launch {
        api.cambiarActivoProducto(p.id, !p.activo).onSuccess { dao.guardarProductos(listOf(it)) }.onFailure { aviso(it.message ?: SIN_CONEXION) }
    }

    // Carrito del usuario conectado.
    val carrito: StateFlow<List<LineaCarrito>> = porUsuario(emptyList()) { if (it.rol.compra) dao.carrito(it.id) else flowOf(emptyList()) }
    // Destino y modalidad del envío elegidos en el carrito.
    val destino = MutableStateFlow(Destino.NACIONAL)
    val modalidad = MutableStateFlow(Modalidad.ESTANDAR)

    // Costo del envío del carrito.
    fun costoEnvio(lineas: List<LineaCarrito>) =
        calcularEnvio(lineas.sumOf { it.pesoGramos * it.cantidad }, lineas.sumOf { it.subtotal }, destino.value, modalidad.value)

    // Agrega un producto al carrito si hay stock.
    fun agregar(p: Producto, cantidad: Int, alTerminar: (Boolean) -> Unit) {
        val u = yo ?: return
        val enCarrito = carrito.value.firstOrNull { it.productoId == p.id }?.cantidad ?: 0
        if (!p.disponible || enCarrito + cantidad > p.stock) { aviso("No hay stock suficiente (quedan ${p.stock})."); alTerminar(false); return }
        viewModelScope.launch { dao.guardarItem(ItemCarrito(u.id, p.id, enCarrito + cantidad)); aviso("Agregado al carrito."); alTerminar(true) }
    }

    // Cambia la cantidad de un producto del carrito (0 = lo quita).
    fun cambiarCantidad(l: LineaCarrito, cantidad: Int) {
        val u = yo ?: return
        if (cantidad > l.stock) { aviso("Solo hay ${l.stock} unidades."); return }
        viewModelScope.launch { if (cantidad <= 0) dao.quitarItem(u.id, l.productoId) else dao.guardarItem(ItemCarrito(u.id, l.productoId, cantidad)) }
    }

    // Confirma la compra, crea la solicitud y avisa al cliente y al Administrador.
    fun comprar() {
        val u = yo?.takeIf { it.rol.compra } ?: return
        viewModelScope.launch {
            val lineas = dao.carritoActual(u.id)
            if (lineas.isEmpty()) { aviso("El carrito está vacío."); return@launch }
            cargando.value = true
            // ms-tienda revisa el stock, calcula el envío, descuenta las unidades y crea la solicitud (todo o nada).
            api.comprar(u.id, lineas, destino.value, modalidad.value)
                .onSuccess { s ->
                    dao.guardarSolicitudes(listOf(s))
                    dao.vaciarCarrito(u.id)
                    sincronizar()  // Actualiza el stock que se ve en el catálogo
                    repo.avisar(Rol.ADMINISTRADOR, "Nueva solicitud #${s.id}", "${u.nombre}: ${clp(s.total)}", "solicitudes", u)
                    repo.avisar(null, "Solicitud recibida", "Tu pedido #${s.id} quedó Pendiente.", "solicitudes", u, listOf(u.id))
                    reemplazar(Pantalla.Solicitudes)
                }
                .onFailure { aviso(it.message ?: "No se pudo comprar.") }
            cargando.value = false
        }
    }

    // Envía una consulta sobre un curso.
    fun pedirInformacion(p: Producto, mensaje: String) {
        val u = yo ?: return
        if (mensaje.trim().length < 10) { aviso("Escribe tu consulta (mínimo 10 caracteres)."); return }
        viewModelScope.launch {
            val s = api.pedirInformacion(u.id, p.id, mensaje).getOrElse { aviso(it.message ?: SIN_CONEXION); return@launch }
            dao.guardarSolicitudes(listOf(s))
            repo.avisar(Rol.ADMINISTRADOR, "Consulta por curso", "${u.nombre} pregunta por «${p.nombre}».", "solicitudes", u)
            aviso("Solicitud de información enviada.")
        }
    }

    // Solicitudes: el Cliente ve las suyas y el Administrador todas.
    val solicitudes: StateFlow<List<SolicitudConCliente>> =
        porUsuario(emptyList()) { dao.solicitudes(if (it.rol.gestiona) null else it.id) }

    // El Administrador cambia el estado de una solicitud y avisa al cliente.
    fun cambiarEstado(s: Solicitud, estado: EstadoSolicitud) {
        val u = yo?.takeIf { it.rol.gestiona } ?: return
        viewModelScope.launch {
            api.cambiarEstadoSolicitud(s.id, estado).getOrElse { aviso(it.message ?: SIN_CONEXION); return@launch }
            dao.cambiarEstado(s.id, estado)
            repo.avisar(null, "Estado de tu pedido", "La solicitud #${s.id} ahora está ${estado.etiqueta}.", "solicitudes", u, listOf(s.usuarioId))
        }
    }

    // ================= Códigos QR
    // Resultado de la última lectura e historial de lecturas.
    val qr = MutableStateFlow<EstadoQr>(EstadoQr.Leyendo)
    val lecturas: StateFlow<List<LecturaQr>> = porUsuario(emptyList()) { dao.lecturas(it.id) }

    // Lee el QR, busca su ficha y la guarda en el historial.
    fun procesarQr(texto: String) {
        val u = yo ?: return
        qr.value = EstadoQr.Leyendo
        ir(Pantalla.ResultadoQr)
        viewModelScope.launch {
            val c = LectorQr.leer(texto).getOrElse { qr.value = EstadoQr.Error(it.message.orEmpty(), texto, null); return@launch }
            val resultado = if (c.tipo == "museo" || c.tipo == "exposicion") {
                buscarMuseo(c.codigo)?.takeIf { u.rol.veNoPublicadas || it.estado == EstadoFicha.PUBLICADO }?.let {
                    EstadoQr.Ok(c, it.nombre, "${it.ciudad}, ${it.pais} · ${it.descripcion}", it.fotoRuta ?: it.imagenUrl,
                        Pantalla.Museo(it.id), it.urlVisita, false)
                }
            } else {
                buscarProducto(c.codigo)?.takeIf { it.activo }?.let {
                    EstadoQr.Ok(c, it.nombre, "${it.categoria.etiqueta} · ${clp(it.precio)} · ${it.descripcion}", null, Pantalla.Producto(it.id), "", false)
                }
            }
            if (resultado == null) { qr.value = EstadoQr.Error("El código ${c.codigo} no corresponde a ninguna ficha registrada.", texto, c); return@launch }
            val destino = (resultado.destino as? Pantalla.Museo)?.let { "museo:${it.id}" } ?: "producto:${(resultado.destino as Pantalla.Producto).id}"
            val nueva = dao.registrarLectura(LecturaQr(usuarioId = u.id, codigo = c.codigo, titulo = resultado.titulo, destino = destino))
            qr.value = resultado.copy(nueva = nueva)
        }
    }

    // Busca el código en ms-museos o ms-tienda y lo guarda en la caché. Sin conexión, usa la copia local.
    private suspend fun buscarMuseo(codigo: String): Museo? =
        api.museoPorCodigo(codigo).onSuccess { dao.guardarMuseos(listOf(it)) }.getOrNull() ?: dao.museoPorCodigo(codigo)
    private suspend fun buscarProducto(codigo: String): Producto? =
        api.productoPorCodigo(codigo).onSuccess { dao.guardarProductos(listOf(it)) }.getOrNull() ?: dao.productoPorCodigo(codigo)

    // ================= Avisos y perfil
    // Avisos del usuario y, para el Administrador, la lista de cuentas.
    val notificaciones: StateFlow<List<Notificacion>> = porUsuario(emptyList()) { dao.notificaciones(it.id) }
    val usuarios: StateFlow<List<Usuario>> = porUsuario(emptyList()) { if (it.rol.gestiona) dao.usuarios() else flowOf(emptyList()) }

    // Marca un aviso como leído (o todos si es null) y abre su pantalla.
    fun leer(n: Notificacion?) { val u = yo ?: return; viewModelScope.launch { dao.marcarLeida(u.id, n?.id) }; n?.destino?.let(::abrirDestino) }

    // El Administrador envía un aviso a un rol o a todos.
    fun enviarAviso(rol: Rol?, titulo: String, mensaje: String) {
        val u = yo?.takeIf { it.rol.gestiona } ?: return
        if (titulo.isBlank() || mensaje.isBlank()) { aviso("Completa título y mensaje."); return }
        viewModelScope.launch { aviso("Aviso enviado a ${repo.avisar(rol, titulo.trim(), mensaje.trim(), "catalogo", u)} perfil(es).") }
    }

    // Guarda los cambios del perfil.
    fun guardarPerfil(nombre: String, avatar: String, notificaciones: Boolean, textoGrande: Boolean, idioma: String) {
        val u = yo ?: return
        Validar.texto(nombre)?.let { aviso("Nombre: $it"); return }
        viewModelScope.launch {
            api.guardarPerfil(u.copy(nombre = nombre.trim(), avatar = avatar, notificaciones = notificaciones, textoGrande = textoGrande, idioma = idioma))
                .onSuccess { dao.guardarUsuario(it); aviso("Perfil guardado ✓") }
                .onFailure { aviso(it.message ?: SIN_CONEXION) }
        }
    }

    // El Administrador activa o desactiva una cuenta (no la suya).
    fun alternarCuenta(c: Usuario) {
        if (c.id == yo?.id) { aviso("No puedes desactivar tu propia cuenta."); return }
        viewModelScope.launch {
            api.cambiarActivoUsuario(c.id, !c.activo).onSuccess { dao.guardarUsuario(it) }.onFailure { aviso(it.message ?: SIN_CONEXION) }
        }
    }
}
