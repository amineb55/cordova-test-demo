package com.actionverite.live.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class ModelInvariantsTest {

    @Test
    fun `connection quality buckets map ping ranges correctly`() {
        assertThat(Player.qualityForPing(-1)).isEqualTo(ConnectionQuality.DISCONNECTED)
        assertThat(Player.qualityForPing(0)).isEqualTo(ConnectionQuality.EXCELLENT)
        assertThat(Player.qualityForPing(80)).isEqualTo(ConnectionQuality.EXCELLENT)
        assertThat(Player.qualityForPing(81)).isEqualTo(ConnectionQuality.GOOD)
        assertThat(Player.qualityForPing(150)).isEqualTo(ConnectionQuality.GOOD)
        assertThat(Player.qualityForPing(151)).isEqualTo(ConnectionQuality.FAIR)
        assertThat(Player.qualityForPing(250)).isEqualTo(ConnectionQuality.FAIR)
        assertThat(Player.qualityForPing(251)).isEqualTo(ConnectionQuality.POOR)
    }

    @Test
    fun `difficulty vote rejects out-of-range values`() {
        assertThrows(IllegalArgumentException::class.java) { DifficultyVote("v", 0) }
        assertThrows(IllegalArgumentException::class.java) { DifficultyVote("v", 6) }
        // Valid range does not throw.
        DifficultyVote("v", 1)
        DifficultyVote("v", 5)
    }

    @Test
    fun `user age is computed deterministically from injected today`() {
        val birth = LocalDate.of(2000, 1, 1).toEpochDay()
        val today = LocalDate.of(2020, 7, 1).toEpochDay() // ~20.5 years later
        val profile = UserProfile(uid = "u", displayName = "Tester", birthEpochDay = birth)

        assertThat(profile.ageOn(today)).isEqualTo(20)
        assertThat(profile.isAdultOn(today)).isTrue()
    }

    @Test
    fun `minor is not adult and has null age when birth undisclosed`() {
        val birth = LocalDate.of(2010, 1, 1).toEpochDay()
        val today = LocalDate.of(2020, 7, 1).toEpochDay()
        val minor = UserProfile(uid = "m", displayName = "Kid", birthEpochDay = birth)
        assertThat(minor.ageOn(today)).isEqualTo(10)
        assertThat(minor.isAdultOn(today)).isFalse()

        val unknown = UserProfile(uid = "x", displayName = "X", birthEpochDay = null)
        assertThat(unknown.ageOn(today)).isNull()
        assertThat(unknown.isAdultOn(today)).isFalse()
    }

    @Test
    fun `room capacity rules reflect the 2 to 6 player spec`() {
        val players = (1..6).map { Player(uid = "p$it", displayName = "P$it") }
        val room = Room(id = "r", hostUid = "p1", visibility = RoomVisibility.PUBLIC, players = players)
        assertThat(room.isFull).isTrue()
        assertThat(room.canStart).isTrue()

        val solo = room.copy(players = players.take(1))
        assertThat(solo.canStart).isFalse()
        assertThat(solo.hasRoom()).isTrue()
    }
}
