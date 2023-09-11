package com.example.registrovacaciones

import android.content.Context
import android.graphics.BitmapFactory
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCapture.OnImageSavedCallback
import androidx.camera.core.ImageCapture.OutputFileOptions
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberImagePainter
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.time.LocalDateTime
import java.util.*

enum class Pantalla {
    FORM,
    CAMARA,
    UBICACION
}

class AppVM : ViewModel() {
    val pantallaActual = mutableStateOf(Pantalla.FORM)
    var onPermisoCamaraOk: () -> Unit = {}
    val latitud = mutableStateOf(0.0)
    val longitud = mutableStateOf(0.0)
    var onPermisoUbicacionOk: () -> Unit = {}
}

class FormRegistroVM : ViewModel() {
    val nombre = mutableStateOf("")
    val fotos = mutableStateListOf<Uri>()
}

class MainActivity : ComponentActivity() {
    val camaraVM: AppVM by viewModels()
    val formRegistroVM: FormRegistroVM by viewModels()
    val appVM:AppVM by viewModels()
    lateinit var cameraController: LifecycleCameraController

    val lanzadorPermisos = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (it[android.Manifest.permission.CAMERA] ?: false) {
            camaraVM.onPermisoCamaraOk()
        }else{
            Log.v("lanzadorPermisos callback", "se deneragon los permisos")
        }
        if((it[android.Manifest.permission.ACCESS_FINE_LOCATION] ?: false) or (it[android.Manifest.permission.ACCESS_COARSE_LOCATION] ?: false)){
            appVM.onPermisoUbicacionOk()
        }else{
            Log.v("lanzadorPermisos callback", "se deneragon los permisos")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraController = LifecycleCameraController(this)
        cameraController.bindToLifecycle(this)
        cameraController.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        setContent {
            AppUI(
                lanzadorPermisos,
                cameraController,
                camaraVM, // Agrega el ViewModel de la cámara
                formRegistroVM, // Agrega el ViewModel del formulario
                this // Agrega el contexto
            )
        }
    }

    class FaltaPermisosException(mensaje:String): Exception(mensaje)

    fun conseguirUbicacion(contexto:Context, onSuccess:(ubicacion: Location) -> Unit){
        try{
            val servicio = LocationServices.getFusedLocationProviderClient(contexto)
            val tarea    = servicio.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                null
            )
            tarea.addOnSuccessListener {
                onSuccess(it)
            }
        }catch (se:SecurityException){
            throw FaltaPermisosException("Sin Permisos de Ubicacion")
        }
    }


    @Composable
    fun AppUI(
        lanzadorPermisos: ActivityResultLauncher<Array<String>>,
        cameraController: LifecycleCameraController,
        appVM: AppVM,
        formRegistroVM: FormRegistroVM,
        contexto: Context
    ) {
        when (appVM.pantallaActual.value) {
            Pantalla.FORM -> {
                PantallaFormUI(appVM, formRegistroVM, contexto)
            }

            Pantalla.CAMARA -> {
                PantallaCamaraUI(lanzadorPermisos, cameraController)
            }

            Pantalla.UBICACION -> {
                PantallaUbicacionUI(appVM, contexto)
            }
        }
    }

    @Composable
    fun PantallaUbicacionUI(appVM: AppVM, contexto: Context) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Latitud: ${appVM.latitud.value}",
                modifier = Modifier.padding(8.dp)
            )
            Text(
                text = "Longitud: ${appVM.longitud.value}",
                modifier = Modifier.padding(8.dp)
            )
            Button(
                onClick = {
                    // Cambiar de vuelta a la pantalla anterior
                    appVM.pantallaActual.value = Pantalla.FORM
                },
                modifier = Modifier.padding(8.dp)
            ) {
                Text("Volver al Formulario")
            }
        }
    }

    fun uri2ImageBitmap(uri: Uri, contexto: Context) = BitmapFactory.decodeStream(
        contexto.contentResolver.openInputStream(uri)
    ).asImageBitmap()

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun PantallaFormUI(
        appVM: AppVM,
        formRegistroVM: FormRegistroVM,
        contexto: Context
    ) {
        // Variable para almacenar el lugar ingresado con persistencia
        var lugarIngresado by rememberSaveable { mutableStateOf("") }

        // Inicializar lugarIngresado solo una vez
        LaunchedEffect(true) {
            lugarIngresado = formRegistroVM.nombre.value
        }

        // Nuevo estado para controlar la vista de pantalla completa
        var pantallaCompleta by remember { mutableStateOf(false) }
        var imagenEnPantallaCompleta by remember { mutableStateOf<Uri?>(null) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Primer campo de texto para ingresar el lugar
            TextField(
                value = lugarIngresado,
                onValueChange = { lugarIngresado = it },
                modifier = Modifier
                    .background(Color.Gray)
                    .fillMaxWidth()
                    .padding(8.dp),
                keyboardOptions = KeyboardOptions.Default.copy(
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (lugarIngresado.isNotEmpty()) {
                            // Agregar el prefijo "Lugar ingresado: "
                            lugarIngresado = "Lugar ingresado: $lugarIngresado"
                        }
                    }
                ),
                textStyle = LocalTextStyle.current.copy(
                    color = Color.White
                ),
                singleLine = true,
                placeholder = { Text("Nombre del lugar visitado") },
            )

            // Segundo campo de texto para mostrar el lugar ingresado
            TextField(
                value = lugarIngresado,
                onValueChange = { /* No hacer nada, solo mostrar el texto */ },
                modifier = Modifier
                    .background(Color.Gray)
                    .fillMaxWidth()
                    .padding(8.dp),
                textStyle = LocalTextStyle.current.copy(
                    color = Color.Black
                ),
                singleLine = true,
                enabled = false // Deshabilitar la edición
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Lista de fotos
            LazyColumn {
                items(formRegistroVM.fotos) { photo ->
                    val index = formRegistroVM.fotos.indexOf(photo) + 1
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Cuando se hace clic en una miniatura, activa la vista de pantalla completa
                        Image(
                            painter = rememberImagePainter(data = photo.toString()), // Cambia esta línea
                            contentDescription = "Foto $index",
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.Gray)
                                .clickable {
                                    pantallaCompleta = true
                                    imagenEnPantallaCompleta = photo
                                }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Foto $index")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Botón para agregar foto
            Button(
                onClick = {
                    appVM.pantallaActual.value = Pantalla.CAMARA
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Text("Agregar Foto")
            }

            Button(
                onClick = {
                    // Cambiar a la pantalla de ubicación
                    appVM.pantallaActual.value = Pantalla.UBICACION
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Text("Ver Ubicación")
            }

            Button(
                onClick = {
                    appVM.onPermisoUbicacionOk = {
                        conseguirUbicacion(contexto){
                            appVM.latitud.value = it.latitude
                            appVM.longitud.value = it.longitude
                        }
                    }
                    lanzadorPermisos.launch(
                        arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Text("Conseguir Ubicación")
            }

            Text("Lat:  ${appVM.latitud.value} Long: ${appVM.longitud.value}")

            Spacer(modifier = Modifier.height(16.dp))

            AndroidView(
                factory = {
                    MapView(it).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        org.osmdroid.config.Configuration.getInstance().userAgentValue = contexto.packageName
                        controller.setZoom(15.0)
                    }
                }, update = {
                    it.overlays.removeIf { true }
                    it.invalidate()

                    val geoPoint = GeoPoint(appVM.latitud.value, appVM.longitud.value)
                    it.controller.animateTo(geoPoint)

                    val marcador = Marker(it)
                    marcador.position = geoPoint
                    marcador.setAnchor(Marker.ANCHOR_CENTER , Marker.ANCHOR_CENTER)
                    it.overlays.add(marcador)
                }
            )

            // Pantalla completa
            if (pantallaCompleta) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .clickable {
                            // Cuando se hace clic en la pantalla completa, desactiva la vista de pantalla completa
                            pantallaCompleta = false
                            imagenEnPantallaCompleta = null
                        }
                ) {
                    imagenEnPantallaCompleta?.let { uri ->
                        val imageBitmap = uri2ImageBitmap(uri, contexto)
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = "Imagen en pantalla completa",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }
    }

    fun generarNombreSegunFechaHastaSegundo(): String = LocalDateTime
        .now().toString().replace(Regex("[T:.-]"), "").substring(0, 14)

    fun crearArchivoImagenPrivado(contexto: Context): File = File(
        contexto.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
        "${generarNombreSegunFechaHastaSegundo()}.jpg"
    )

    fun capturarFotografia(
        cameraController: LifecycleCameraController,
        archivo: File,
        contexto: Context,
        onImagenGuardada: (uri: Uri) -> Unit
    ) {
        val opciones = OutputFileOptions.Builder(archivo).build()
        cameraController.takePicture(
            opciones,
            ContextCompat.getMainExecutor(contexto),
            object : OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    outputFileResults.savedUri?.let {
                        onImagenGuardada(it)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(
                        "capturarFotografia::OnImageSavedCallback::onError",
                        exception.message ?: "Error"
                    )
                }
            }
        )
    }

    @Composable
    fun PantallaCamaraUI(
        lanzadorPermisos: ActivityResultLauncher<Array<String>>,
        cameraController: LifecycleCameraController
    ) {
        val contexto = LocalContext.current
        val formRegistroVM: FormRegistroVM = viewModel()
        val appVM: AppVM = viewModel()

        lanzadorPermisos.launch(arrayOf(android.Manifest.permission.CAMERA))

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                PreviewView(it).apply {
                    controller = cameraController
                }
            }
        )

        Button(onClick = {
            capturarFotografia(
                cameraController,
                crearArchivoImagenPrivado(contexto),
                contexto
            ) { uri ->
                formRegistroVM.fotos.add(uri) // Agrega la foto tomada a la lista
                appVM.pantallaActual.value = Pantalla.FORM // Vuelve a la pantalla de formulario
            }
        }) {
            Text("Tomar Foto")
        }
    }
}

