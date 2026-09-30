package edu.pickupauth

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Pantalla mínima de etiquetado (muestreo de experiencia). Se abre desde la notificación tras un desbloqueo. */
class LabelActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dirName = intent.getStringExtra(EXTRA_DIR) ?: run { finish(); return }
        val writer = EpisodeWriter(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 96, 48, 48)
        }
        layout.addView(TextView(this).apply {
            text = "¿De dónde tomaste el teléfono en el último desbloqueo?"
            textSize = 18f
            setPadding(0, 0, 0, 32)
        })
        for ((code, label) in LABELS) {
            layout.addView(Button(this).apply {
                text = label
                setOnClickListener { writer.writeLabel(dirName, code); finish() }
            })
        }
        setContentView(layout)
    }

    companion object {
        const val EXTRA_DIR = "dir"
        val LABELS = listOf(
            "pocket_front" to "Bolsillo delantero del pantalón",
            "pocket_back" to "Bolsillo trasero",
            "jacket" to "Chaqueta / camisa",
            "bag" to "Bolsa / mochila",
            "table" to "Mesa u otra superficie",
            "hand" to "Ya estaba en mi mano",
            "not_lifted" to "No lo levanté (lo usé donde estaba)",
            "not_owner" to "No fui yo (otra persona)",
            "unsure" to "No recuerdo"
        )
    }
}
