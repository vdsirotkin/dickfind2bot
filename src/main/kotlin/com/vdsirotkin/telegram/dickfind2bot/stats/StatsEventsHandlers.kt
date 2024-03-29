package com.vdsirotkin.telegram.dickfind2bot.stats

import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class StatsEventsHandlers(
    private val statsService: StatsService
) {

    @EventListener(FoundDickEvent::class)
    fun foundDickEventHandler(event: FoundDickEvent) {
        val (chatId, userId) = event
        val id = UserAndChatId(chatId, userId)
        statsService.incrementDicks(id)
    }

    @EventListener(FoundGoldenDickEvent::class)
    fun foundGoldenDickEventHandler(event: FoundGoldenDickEvent) {
        val (chatId, userId) = event
        val id = UserAndChatId(chatId, userId)
        statsService.incrementGoldenDicks(id)
    }

    @EventListener(FoundNothingEvent::class)
    fun foundNothingEventHandler(event: FoundNothingEvent) {
        val (chatId, userId) = event
        val id = UserAndChatId(chatId, userId)
        statsService.incrementNothing(id)
    }

    @EventListener(FoundBombEvent::class)
    fun foundBombEventHandler(event: FoundBombEvent) {
        val (chatId, userId) = event
        val id = UserAndChatId(chatId, userId)
        statsService.incrementBombs(id)
    }

    @EventListener(GameFinishedEvent::class)
    fun gameFinishedEventHandler(event: GameFinishedEvent) {
        val chatId = event.chatId
        val winnerId = UserAndChatId(chatId, event.userWonId)
        val loserId = UserAndChatId(chatId, event.userLostId)
        statsService.saveGameResults(winnerId, loserId)
    }

    @EventListener(GameFinishedDrawEvent::class)
    fun gameFinishedDrawHandler(event: GameFinishedDrawEvent) {
        val chatId = event.chatId
        val firstPlayerId = UserAndChatId(chatId, event.firstUserId)
        val secondPlayerId = UserAndChatId(chatId, event.secondUserId)
        statsService.saveDrawGameResults(firstPlayerId, secondPlayerId);
    }

    @EventListener(GameFinishedBothLoseEvent::class)
    fun handleGameFinishedBothLoseEvent(event: GameFinishedBothLoseEvent) {
        val chatId = event.chatId
        val firstPlayerId = UserAndChatId(chatId, event.firstUserId)
        val secondPlayerId = UserAndChatId(chatId, event.secondUserId)
        statsService.saveBothLose(firstPlayerId, secondPlayerId);
    }

}
