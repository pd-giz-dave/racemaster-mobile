package mobile.racemaster.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mobile.racemaster.R
import mobile.racemaster.ui.theme.RaceMasterGreen

/**
 * Always-visible app banner shown above every screen, styled to match the RaceMaster web
 * app's header (solid brand green, white icon + wordmark).
 */
@Composable
fun AppBanner(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(RaceMasterGreen)
            .statusBarsPadding()
            .height(48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_racemaster_banner),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
        )
        Text(
            "RaceMaster Mobile",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
        )
    }
}