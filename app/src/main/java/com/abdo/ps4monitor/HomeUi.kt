@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package com.abdo.ps4monitor
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun go(nav: NavController, r: String) = nav.navigate(r) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }

@Composable fun HomeScreen(nav: NavController) {
    val ps4s by Ps4Repo.list.collectAsState()
    val activeId by Ps4Repo.activeId.collectAsState()
    val statuses by DownloadMonitor.status.collectAsState()
    val dls by DownloadRepo.all.collectAsState()
    val ps4 = ps4s.firstOrNull { it.id == activeId }
    val st = ps4?.let { statuses[it.id] } ?: Ps4Status()
    val scope = rememberCoroutineScope()
    var probing by remember { mutableStateOf(false) }
    var probeMsg by remember { mutableStateOf("") }
    var pick by remember { mutableStateOf(false) }
    var waking by remember { mutableStateOf(false) }
    var wakeMsg by remember { mutableStateOf("") }
    var pauseId by remember { mutableStateOf<String?>(null) }
    var resumeId by remember { mutableStateOf<String?>(null) }
    fun refresh() { val p = ps4 ?: return; probing = true; scope.launch { probeMsg = DownloadMonitor.probe(p); probing = false } }
    LaunchedEffect(ps4?.id) { if (ps4 != null) refresh() }
    var srvInfo by remember { mutableStateOf<Ps4Server.ServerInfo?>(null) }
    var srvChecked by remember { mutableStateOf(false) }
    var srvTick by remember { mutableIntStateOf(0) }
    var showPicker by remember { mutableStateOf(false) }
    LaunchedEffect(ps4?.id, probing, srvTick) {
        val p = ps4 ?: return@LaunchedEffect
        if (!probing) { srvInfo = withContext(Dispatchers.IO) { Ps4Server.info(p, 3000) }; srvChecked = true }
    }

    val mine = dls.filter { it.ps4Id == ps4?.id }
    val active = mine.filter { it.state.active }
    val paused = mine.filter { it.state == DlState.PAUSED }
    val recent = mine.filter { !it.state.active && it.state != DlState.PAUSED }.sortedByDescending { it.updatedAt }.take(4)
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { ClipCard() }
        item {
            val cs = MaterialTheme.colorScheme; val k = LocalTokens.current
            if (ps4 == null) Column(Modifier.fillMaxWidth().dnaCard(24).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Ico(R.drawable.ic_console, 32.dp, cs.primary); Text(tr("No PS4 yet", "لا يوجد PS4 بعد"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Text(tr("Add your PS4 (IP address) to send and monitor downloads.", "أضف جهاز PS4 (عنوان IP) لإرسال التحميلات ومراقبتها."), color = cs.onSurfaceVariant)
                PrimaryAction(true, Modifier.fillMaxWidth(), { nav.navigate("settings/ps4/new") }) { Ico(R.drawable.ic_add, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Add PS4", "إضافة PS4"), weight = FontWeight.SemiBold) }
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // console identity + connection state
                val (pillText, pillColor) = when {
                    !srvChecked || probing -> tr("Checking…", "جارٍ الفحص…") to cs.onSurfaceVariant
                    srvInfo != null -> tr("Connected", "متصل") to k.success
                    st.power == Power.REST -> tr("Sleeping", "وضع الراحة") to k.warning
                    else -> tr("Unavailable", "غير متاح") to k.error
                }
                Row(Modifier.fillMaxWidth().dnaCard(24, raised = true).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(64.dp).dnaCard(18, fill = Brush.linearGradient(listOf(cs.primaryContainer, cs.surfaceContainerHighest))), contentAlignment = Alignment.Center) { Ico(R.drawable.ic_console, 34.dp, cs.onSurface) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(ps4.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(ps4.host, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (ps4s.size > 1) Box {
                            Text(tr("Switch console", "تبديل الجهاز") + "  ▾", Modifier.clickable { pick = true }.padding(top = 4.dp), color = cs.primary, style = MaterialTheme.typography.labelLarge)
                            DropdownMenu(pick, { pick = false }) { ps4s.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { Ps4Repo.setActive(p.id); pick = false }) } }
                        }
                    }
                    StatusPill(pillText, pillColor)
                }
                // server + power + actions
                Column(Modifier.fillMaxWidth().dnaCard(24, fill = SolidColor(cs.surfaceContainer)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val srvName = when {
                        !srvChecked -> tr("checking…", "جارٍ الفحص…")
                        srvInfo == null -> tr("not reachable", "غير متاح")
                        srvInfo!!.locked -> tr("custom (token required)", "مخصص (يطلب رمزًا)")
                        srvInfo!!.custom -> tr("Our server ", "سيرفرنا ") + srvInfo!!.version
                        else -> tr("Older server", "PS4 server الأصلي")
                    }
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(cs.surfaceContainerHigh).clickable { showPicker = true }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Dim(tr("Server in use", "السيرفر المستخدم"))
                            Text(srvName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        HelpDot(tr("Server in use", "السيرفر المستخدم"), tr("The server that receives your downloads. Tap the row to switch between our server and others you have set up.", "السيرفر الذي يستقبل تحميلاتك. اضغط الصف للتبديل بين سيرفرنا وسيرفرات أخرى أعددتها."))
                        Lbl(tr("Change", "تغيير"), color = cs.primary)
                        Ico(R.drawable.ic_arrow_forward, 18.dp, cs.primary)
                    }
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().clip(CircleShape).background(pillColor))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(tr("PS4 power", "حالة الـPS4") + ": ${st.power.label}", fontWeight = FontWeight.SemiBold)
                            Dim(tr("PS4 server", "خادم PS4") + ": ${st.bg.label}")
                            if (waking) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (wakeMsg.isNotBlank()) Text(wakeMsg, style = MaterialTheme.typography.bodySmall)
                            if (probing) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else if (probeMsg.isNotBlank()) Text(Tx.lines(probeMsg), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                    val canWake = st.power == Power.REST || st.power == Power.OFF
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryAction(!probing, Modifier.weight(1f), { refresh() }) { Ico(R.drawable.ic_refresh, 20.dp); Spacer(Modifier.width(8.dp)); Lbl(tr("Refresh", "تحديث"), weight = FontWeight.SemiBold) }
                        if (canWake) SecondaryAction(!waking, true, Modifier.weight(1f), { waking = true; wakeMsg = ""; scope.launch { wakeMsg = DownloadMonitor.wakePs4(ps4); waking = false; refresh() } }) { Lbl(tr("Wake PS4", "إيقاظ الـPS4"), weight = FontWeight.SemiBold) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryAction(true, false, Modifier.weight(1f), { nav.navigate("settings/ps4/${ps4.id}") }) { Ico(R.drawable.ic_edit, 18.dp); Spacer(Modifier.width(8.dp)); Lbl(tr("Edit", "تعديل")) }
                        SecondaryAction(true, false, Modifier.weight(1f), { nav.navigate("settings/ps4/new") }) { Ico(R.drawable.ic_add, 18.dp); Spacer(Modifier.width(8.dp)); Lbl(tr("Add PS4", "إضافة PS4")) }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile(R.drawable.ic_link, tr("Link", "رابط"), Modifier.weight(1f)) { Inbox.url.value = "" }
                    ActionTile(R.drawable.ic_globe, tr("Browser", "المتصفح"), Modifier.weight(1f)) { go(nav, "browser") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile(R.drawable.ic_download, tr("Downloads", "التحميلات"), Modifier.weight(1f)) { go(nav, "downloads") }
                    ActionTile(R.drawable.ic_folder, tr("Files", "الملفات"), Modifier.weight(1f)) { nav.navigate("files") }
                }
            }
        }
        item {
            val cs = MaterialTheme.colorScheme
            Row(Modifier.fillMaxWidth().dnaCard(18).clickable { nav.navigate("ps4info") }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(46.dp).clip(dnaShape(23)).background(cs.primaryContainer), contentAlignment = Alignment.Center) { Ico(R.drawable.ic_console, 26.dp, cs.primary) }
                Column(Modifier.weight(1f)) {
                    Text(tr("PS4 information", "معلومات الـPS4"), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    Dim(tr("Screenshots, videos, storage, installed games, device", "لقطات الشاشة، الفيديوهات، التخزين، الألعاب المثبتة، الجهاز"))
                }
                Ico(R.drawable.ic_arrow_forward, 20.dp, cs.outline)
            }
        }
        item { SectionHeader(tr("Active downloads", "التحميلات النشطة") + " (${active.size})") }
        if (active.isEmpty()) item { EmptyState(R.drawable.ic_download, tr("Nothing is being downloaded.", "لا يوجد تحميل جارٍ.")) }
        items(active.take(3), key = { it.id }) { DownloadCard(it, { nav.navigate("downloads/${it.id}") }, onPause = { pauseId = it.id }) }
        if (active.size > 3) item { TextButton(onClick = { go(nav, "downloads") }) { Lbl(tr("View all", "عرض الكل") + " ${active.size}") } }
        if (paused.isNotEmpty()) item { SectionHeader(tr("Paused", "متوقفة") + " (${paused.size})") }
        items(paused.take(3), key = { "p" + it.id }) { DownloadCard(it, { nav.navigate("downloads/${it.id}") }, onResume = { resumeId = it.id }) }
        if (recent.isNotEmpty()) item { SectionHeader(tr("Recent downloads", "آخر التحميلات")) }
        items(recent, key = { "r" + it.id }) { DownloadCard(it, { nav.navigate("downloads/${it.id}") }) }
    }
    if (showPicker && ps4 != null) ServerPickerSheet(ps4, srvInfo, onDismiss = { showPicker = false }, onChanged = { srvTick++; refresh() })
    pauseId?.let { ConfirmPause(it) { pauseId = null } }
    resumeId?.let { ConfirmResume(it) { resumeId = null } }
}
