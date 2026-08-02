package com.jirofeingold.pairfortwo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jirofeingold.pairfortwo.core.ScoreFlag
import com.jirofeingold.pairfortwo.core.totalPoints
import com.jirofeingold.pairfortwo.ui.theme.CribGold
import com.jirofeingold.pairfortwo.ui.theme.FeltDark

/**
 * The coach's flag chips — every scoring opportunity the engine detected for the current context.
 * Port of the iOS `ScoreFlagsView`.
 *
 * Flag-only: they inform, they never apply themselves. Tinted in the scoring player's colour and led
 * by their name, so it is clear whose points these are.
 */
@Composable
fun ScoreFlagsView(
    flags: List<ScoreFlag>,
    modifier: Modifier = Modifier,
    accent: Color = CribGold,
    playerName: String? = null,
) {
    if (flags.isEmpty()) return

    Row(
        modifier
            .height(30.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (playerName != null) {
            Text(
                playerName.uppercase(),
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(end = 2.dp),
            )
        }

        for (flag in flags) {
            Row(
                Modifier
                    .background(accent, CircleShape)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    flag.detail,
                    color = Color.Black.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (flag.points > 0) {
                    Text(
                        "+${flag.points}",
                        color = Color.Black.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        }

        // Running total of the detected points.
        if (flags.size > 1) {
            Text(
                "= ${flags.totalPoints}",
                color = Color.Black,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .background(Color.White, CircleShape)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D211A, widthDp = 420)
@Composable
private fun ScoreFlagsPreview() {
    Row(Modifier.background(FeltDark).padding(8.dp)) {
        ScoreFlagsView(
            flags = listOf(
                ScoreFlag(ScoreFlag.Kind.FIFTEEN, 2, "Fifteen 2"),
                ScoreFlag(ScoreFlag.Kind.PAIR, 2, "Pair"),
                ScoreFlag(ScoreFlag.Kind.RUN, 3, "Run of 3"),
            ),
            playerName = "Ada",
        )
    }
}
