package com.example.reproductor

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import android.media.MediaPlayer
import android.net.Uri
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.*
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var mediaPlayer: MediaPlayer
    private var pausado = false
    private var progressJob: Job? = null
    private val REQUEST_CODE_PICK_AUDIO = 1001
    private var currentSongIndex = 0
    private lateinit var adapter: ArrayAdapter<String>
    private val cancionesDir by lazy { File(filesDir, "canciones").apply { mkdirs() } }
    private val cancionesFile by lazy { File(cancionesDir, "playlist.txt") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val listaCancionesView = findViewById<ListView>(R.id.playList)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listaCancionesView.adapter = adapter

        // Reproducir canción al hacer click
        listaCancionesView.setOnItemClickListener { _, _, position, _ ->
            currentSongIndex = position
            loadSong(listaCanciones[currentSongIndex].uri)
        }

        // Eliminar canción con click largo
        listaCancionesView.setOnItemLongClickListener { _, _, position, _ ->
            AlertDialog.Builder(this)
                .setTitle("Eliminar Canción")
                .setMessage("¿Desea eliminar esta canción?")
                .setPositiveButton("Eliminar") { _, _ ->
                    listaCanciones.removeAt(position)
                    actualizarListaDeCanciones()
                    guardarListaCanciones()
                }
                .setNegativeButton("Cancelar", null)
                .show()
            true
        }

        val btnSeleccionarCancion = findViewById<Button>(R.id.btnAddSong)
        btnSeleccionarCancion.setOnClickListener {
            selectSong()
        }

        findViewById<ImageButton>(R.id.btnStartStop).setOnClickListener {
            startStop()
        }

        findViewById<ImageButton>(R.id.btnNext).setOnClickListener {
            nextSong()
        }

        findViewById<ImageButton>(R.id.btnPrevious).setOnClickListener {
            previousSong()
        }

        cargarListaCanciones()
    }

    fun selectSong() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        startActivityForResult(intent, REQUEST_CODE_PICK_AUDIO)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_PICK_AUDIO && resultCode == RESULT_OK) {
            val selectedAudioUri: Uri? = data?.data
            if (selectedAudioUri != null) {
                mostrarDialogoTitulo(selectedAudioUri)
            }
        }
    }

    fun mostrarDialogoTitulo(uri: Uri) {
        val input = EditText(this)
        input.hint = "Introduce el título de la canción"

        AlertDialog.Builder(this)
            .setTitle("Agregar Canción")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val titulo = input.text.toString()
                if (titulo.isNotEmpty()) {
                    val savedUri = copiarArchivo(uri, titulo)
                    agregarCancionALista(titulo, savedUri)
                    guardarListaCanciones()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun copiarArchivo(uri: Uri, titulo: String): Uri {
        val inputStream: InputStream? = contentResolver.openInputStream(uri)
        val destino = File(cancionesDir, "$titulo.mp3")
        val outputStream = FileOutputStream(destino)

        inputStream?.use { input ->
            outputStream.use { output ->
                input.copyTo(output)
            }
        }
        return Uri.fromFile(destino)
    }

    data class Cancion(val titulo: String, val uri: Uri)
    val listaCanciones = mutableListOf<Cancion>()

    fun agregarCancionALista(titulo: String, uri: Uri) {
        listaCanciones.add(Cancion(titulo, uri))
        actualizarListaDeCanciones()
    }

    fun actualizarListaDeCanciones() {
        val listaTitulos = listaCanciones.map { it.titulo }
        adapter.clear()
        adapter.addAll(listaTitulos)
        adapter.notifyDataSetChanged()
    }

    private fun guardarListaCanciones() {
        cancionesFile.writeText(listaCanciones.joinToString("\n") { "${it.titulo}|${it.uri}" })
    }

    private fun cargarListaCanciones() {
        if (cancionesFile.exists()) {
            cancionesFile.readLines().forEach { line ->
                val parts = line.split("|")
                if (parts.size == 2) {
                    listaCanciones.add(Cancion(parts[0], Uri.parse(parts[1])))
                }
            }
            actualizarListaDeCanciones()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::mediaPlayer.isInitialized) {
            mediaPlayer.release()
        }
        progressJob?.cancel()
    }

    private fun startStop() {
        if (::mediaPlayer.isInitialized) {
            if (!pausado) {
                mediaPlayer.pause()
                pausado = true
                progressJob?.cancel()
            } else {
                mediaPlayer.start()
                pausado = false

                progressJob = CoroutineScope(Dispatchers.Main).launch {
                    while (mediaPlayer.isPlaying) {
                        val songProgressBar = findViewById<ProgressBar>(R.id.songProgressBar)
                        songProgressBar.progress = mediaPlayer.currentPosition
                        delay(500)
                    }
                }
            }
        }
    }

    private fun nextSong() {
        if (listaCanciones.isNotEmpty()) {
            currentSongIndex = (currentSongIndex + 1) % listaCanciones.size
            loadSong(listaCanciones[currentSongIndex].uri)
        }
    }

    private fun previousSong() {
        if (listaCanciones.isNotEmpty()) {
            currentSongIndex = if (currentSongIndex - 1 < 0) listaCanciones.size - 1 else currentSongIndex - 1
            loadSong(listaCanciones[currentSongIndex].uri)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun loadSong(uri: Uri) {
        if (::mediaPlayer.isInitialized) {
            mediaPlayer.release()
        }

        mediaPlayer = MediaPlayer().apply {
            setDataSource(applicationContext, uri)
            prepare()
            start()
        }

        val songProgressBar = findViewById<ProgressBar>(R.id.songProgressBar)
        val songDuration = mediaPlayer.duration

        songProgressBar.setOnTouchListener { view, motionEvent ->
            if (motionEvent.action == MotionEvent.ACTION_DOWN || motionEvent.action == MotionEvent.ACTION_MOVE) {
                val progressBarWidth = view.width
                val progressBarHeight = view.height

                val centerX = progressBarWidth / 2f
                val centerY = progressBarHeight / 2f

                val touchX = motionEvent.x - centerX
                val touchY = motionEvent.y - centerY

                val touchDistance = sqrt(touchX * touchX + touchY * touchY)

                val outerRadius = min(centerX, centerY)
                val innerRadius = outerRadius * 0.7f

                if (touchDistance in innerRadius..outerRadius) {
                    var angle = Math.toDegrees(atan2(touchY.toDouble(), touchX.toDouble())).toFloat()
                    angle += 90f
                    if (angle < 0) {
                        angle += 360f
                    }
                    val newProgress = ((angle / 360) * songProgressBar.max).toInt()
                    songProgressBar.progress = newProgress
                    if (::mediaPlayer.isInitialized) {
                        mediaPlayer.seekTo(newProgress)
                    }
                    view.performClick()
                    return@setOnTouchListener true
                }
            }
            false
        }

        songProgressBar.setOnClickListener { }
        songProgressBar.max = songDuration
        songProgressBar.progress = 0

        mediaPlayer.start()

        progressJob?.cancel()
        progressJob = CoroutineScope(Dispatchers.Main).launch {
            while (mediaPlayer.isPlaying) {
                songProgressBar.progress = mediaPlayer.currentPosition
                delay(500)
            }
            songProgressBar.progress = songDuration
        }
    }
}
