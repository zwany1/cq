package com.zengqi.ai.feature.chat.ui.screen

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zengqi.ai.common.SecureLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 位置分享数据。
 */
data class SharedLocation(
    val latitude: Double,
    val longitude: Double,
    val address: String?
)

/**
 * 位置选点对话框：定位当前位置 → Geocoder 反查地址 → 预览分享。
 *
 * 用 Android 自带 LocationManager + Geocoder，无 GMS/第三方依赖。
 */
@Composable
fun LocationPickerDialog(
    onDismiss: () -> Unit,
    onShare: (String) -> Unit
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var location by remember { mutableStateOf<SharedLocation?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) {
            getCurrentLocation(context)
        }
        location = result
        loading = false
        if (result == null) {
            error = "定位失败，请检查设备定位服务是否开启"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分享位置", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    loading -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("正在获取当前位置……", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    error != null -> Text(
                        error ?: "",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                    location != null -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            location?.address ?: "未知位置",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "%.4f, %.4f".format(location?.latitude, location?.longitude),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (location != null) {
                Button(onClick = {
                    val loc = location
                    if (loc != null) {
                        val addr = loc.address ?: "未知位置"
                        onShare("[位置] $addr（${"%.4f".format(loc.latitude)},${"%.4f".format(loc.longitude)}）")
                    }
                }) { Text("发送") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

/**
 * 获取当前位置并反查地址。
 * GPS 优先，网络/被动源兜底；短超时单次定位。
 */
private fun getCurrentLocation(context: Context): SharedLocation? {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return null

    val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
    )

    var lastFix: Location? = null
    for (provider in providers) {
        try {
            if (!locationManager.isProviderEnabled(provider)) continue
            val fix = locationManager.getLastKnownLocation(provider)
            if (fix != null && (lastFix == null || fix.time > lastFix.time)) {
                lastFix = fix
            }
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    val fix = lastFix ?: return null

    val address = reverseGeocode(context, fix.latitude, fix.longitude)
    return SharedLocation(fix.latitude, fix.longitude, address)
}

/**
 * Geocoder 反查地址，失败返回 null。
 */
private fun reverseGeocode(context: Context, lat: Double, lng: Double): String? {
    return try {
        if (!Geocoder.isPresent()) return null
        val results = Geocoder(context, Locale.CHINA)
            .getFromLocation(lat, lng, 1)
        results?.firstOrNull()?.let { addr ->
            listOfNotNull(
                addr.locality,
                addr.subLocality,
                addr.thoroughfare?.let { t -> addr.subThoroughfare?.let { "$t$it" } ?: t }
            ).joinToString("").ifBlank { null }
        }
    } catch (e: Exception) {
        SecureLog.w("LocationPicker", "reverse geocode failed: ${e.message}")
        null
    }
}
