<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
xmlns:app="http://schemas.android.com/apk/res-auto"
android:layout_width="match_parent"
android:layout_height="match_parent"
android:fillViewport="true">

<LinearLayout
android:layout_width="match_parent"
android:layout_height="wrap_content"
android:orientation="vertical"
android:padding="8dp">

<LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal">
<EditText android:id="@+id/etUrlBar" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:hint="Escribí una URL" android:inputType="textUri" android:singleLine="true" />
<Button android:id="@+id/btnGo" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Ir" />
</LinearLayout>

<LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal">
<Button android:id="@+id/btnBack" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:text="◀" />
<Button android:id="@+id/btnForward" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:text="▶" />
<Button android:id="@+id/btnReload" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:text="↻" />
<Button android:id="@+id/btnHome" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:text="Home" />
<Button android:id="@+id/btnShowPlayer" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:text="Video" />
<Button android:id="@+id/btnShowBrowser" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:text="Web" />
</LinearLayout>

<WebView android:id="@+id/webView" android:layout_width="match_parent" android:layout_height="400dp" />

<LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal" android:layout_marginTop="8dp">
<com.google.android.material.textfield.TextInputEditText android:id="@+id/etM3u8Url" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:hint="Pegá acá tu link .m3u8 / .mp4 / o página web" android:inputType="textUri" android:singleLine="true" />
<Button android:id="@+id/btnPaste" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Pegar" />
</LinearLayout>

<LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal">
<Button android:id="@+id/btnPlayM3u8" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:text="▶ Reproducir" />
<Button android:id="@+id/btnPlayInWebView" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:text="🌐 En WebView" />
<Button android:id="@+id/btnFavorite" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="★" />
<androidx.mediarouter.app.MediaRouteButton android:id="@+id/btnCast" android:layout_width="wrap_content" android:layout_height="wrap_content" />
</LinearLayout>

<Button android:id="@+id/btnCastAny" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="📺 Transmitir a CUALQUIER TV (Concesionaria)" android:backgroundTint="#C62828" android:textColor="#FFFFFF" />

<Button android:id="@+id/btnAnalyze" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="🔍 Analizar enlace" />
<TextView android:id="@+id/tvAnalysis" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Sin analizar" android:textSize="13sp" android:padding="8dp" />
<Spinner android:id="@+id/spinnerVariants" android:layout_width="match_parent" android:layout_height="wrap_content" android:visibility="gone" />

<Button android:id="@+id/btnOpenPage" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="🌐 Abrir página" />
<Button android:id="@+id/btnDetect15" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="⏱️ Detectar videos (15 seg)" />
<TextView android:id="@+id/tvDetected" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Sin videos detectados" android:textSize="13sp" android:padding="8dp" />

<TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:text="👇 Tocá el video que querés probar:" android:textStyle="bold" android:padding="4dp" />
<LinearLayout android:id="@+id/llDetectedList" android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical" />

<LinearLayout android:id="@+id/playerContainer" android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical" android:visibility="gone">
<androidx.media3.ui.PlayerView android:id="@+id/playerView" android:layout_width="match_parent" android:layout_height="260dp" app:show_buffering="always" app:use_controller="true" />
<TextView android:id="@+id/tvStatus" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Pegá un link .m3u8 y dale Reproducir" android:textSize="14sp" />
<Button android:id="@+id/btnClosePlayer" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Cerrar reproductor" />
</LinearLayout>

<TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:text="📋 Errores / Logs:" android:textStyle="bold" android:layout_marginTop="16dp" />
<LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal">
<Button android:id="@+id/btnCopyLogs" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:text="📋 Copiar logs" />
<Button android:id="@+id/btnClearLogs" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:text="🗑️ Limpiar" />
</LinearLayout>
<ScrollView android:layout_width="match_parent" android:layout_height="300dp" android:background="#111111">
<TextView android:id="@+id/tvLogs" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Sin logs todavía..." android:textColor="#00FF00" android:textSize="11sp" android:padding="8dp" android:textIsSelectable="true" />
</ScrollView>

</LinearLayout>
</ScrollView>