package com.vdsirotkin.telegram.dickfind2bot

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.UpdatesListener
import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.Chat
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.EditMessageText
import com.pengrad.telegrambot.request.GetChatMember
import com.pengrad.telegrambot.request.SendMessage
import com.vdsirotkin.telegram.dickfind2bot.config.MessageBus
import com.vdsirotkin.telegram.dickfind2bot.engine.Entity
import com.vdsirotkin.telegram.dickfind2bot.engine.Entity.UNKNOWN
import com.vdsirotkin.telegram.dickfind2bot.engine.Game
import com.vdsirotkin.telegram.dickfind2bot.engine.GameEngine
import com.vdsirotkin.telegram.dickfind2bot.engine.Round
import com.vdsirotkin.telegram.dickfind2bot.stats.*
import com.vdsirotkin.telegram.dickfind2bot.util.executeSafe
import mu.KLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import javax.annotation.PostConstruct

@Component
class DickfindBot(
    botConfig: BotConfig,
    private val gameEngine: GameEngine,
    private val messageBus: MessageBus,
    private val statsService: StatsService,
    @Qualifier("chatBotAfterRoundDelayExecutor") private val executorService: ThreadPoolTaskScheduler
) : TelegramBot(botConfig.token) {

    @PostConstruct
    fun init() {
        setUpdatesListener {
            it.forEach {
                try {
                    onUpdate(it)
                } catch (e: Exception) {
                    logger.error(e) {}
                }
            }
            UpdatesListener.CONFIRMED_UPDATES_ALL
        }
    }

    private fun onUpdate(update: Update) {
        if (!isGroup(update)) {
            return
        }
        when {
            update.message()?.text()?.startsWith("/duel") == true -> startDuel(update.message())
            update.message()?.text()?.startsWith("/dickstats") == true -> getStats(update.message())
            update.message()?.text()?.startsWith("/top") == true -> handleTop(update.message())
            update.callbackQuery()?.data()?.startsWith("join") == true -> joinGame(update.callbackQuery())
            update.callbackQuery()?.data()?.startsWith("turn") == true -> handleTurn(update.callbackQuery())
        }
    }

    private fun handleTop(message: Message) {
        val chatId = message.chat().id()
        val text = statsService.getTopStats(chatId)
            .mapNotNull {
                val member = this.executeSafe(GetChatMember(chatId, it.userId))
                if (member != null && member.isOk) {
                    member.chatMember().user().firstName() to it.wins
                } else null
            }
            .sortedByDescending { it.second }
            .mapIndexed { index, pair -> "${index + 1}. ${pair.first} - ${pair.second}" }
            .joinToString(separator = "\n")
        executeSafe(SendMessage(chatId, text))
    }

    private fun startDuel(message: Message) {
        val response = executeSafe(SendMessage(
            message.chat().id(),
            "${message.from().firstName()} хочет поискать писюны. Кто тож?"
        ).replyMarkup(InlineKeyboardMarkup().addRow(InlineKeyboardButton("Присоединиться").callbackData("join"))))
        val gameId = retrieveGameId(response.message())
        gameEngine.startNewEmptyGame(gameId, message.from().id(), message.from().firstName())
    }

    private fun getStats(message: Message) {
        executeSafe(SendMessage(
            message.chat().id(),
            statsService.getStats(UserAndChatId(message.chat().id(), message.from().id()),
                message.from().firstName()))
        );
    }

    private fun joinGame(callbackQuery: CallbackQuery) {
        val gameID = retrieveGameId(callbackQuery.message())
        val game = gameEngine.secondPlayerJoin(gameID, callbackQuery.from().id(), callbackQuery.from().firstName())
        if (game.secondPlayer != null) {
            sendNewRound(callbackQuery, game)
        }
    }

    private fun sendNewRound(callbackQuery: CallbackQuery, game: Game) {
        val currentRound = gameEngine.getCurrentRound(game, retrieveGameId(callbackQuery.message()))
        executeSafe(EditMessageText(callbackQuery.message().chat().id(), callbackQuery.message().messageId(), """
                Дуэль. Раунд ${currentRound.order}
                
                ${game.firstPlayer.firstName} - ${game.firstPlayer.score}/3
                ${game.secondPlayer?.firstName} - ${game.secondPlayer?.score}/3
            """.trimIndent()).replyMarkup(InlineKeyboardMarkup().apply {
            repeat(3) {
                addRow(InlineKeyboardButton(UNKNOWN.value).callbackData("turn_${it}_0"), InlineKeyboardButton(UNKNOWN.value).callbackData("turn_${it}_1"), InlineKeyboardButton(UNKNOWN.value).callbackData("turn_${it}_2"))
            }
        }))
    }

    private fun handleTurn(callbackQuery: CallbackQuery) {
        val split = callbackQuery.data().split("_")
        val gameId = retrieveGameId(callbackQuery.message())
        val usersTurnResult = gameEngine.usersTurn(gameId, callbackQuery.from().id(), split[1].toInt() to split[2].toInt())
        if (usersTurnResult == UNKNOWN) return
        executeSafe(AnswerCallbackQuery(callbackQuery.id()).text("Ты нашел $usersTurnResult"))
        when (usersTurnResult) {
            Entity.DICK -> messageBus.publish(FoundDickEvent(callbackQuery.message().chat().id(), callbackQuery.from().id()))
            Entity.NOTHING -> messageBus.publish(FoundNothingEvent(callbackQuery.message().chat().id(), callbackQuery.from().id()))
            Entity.GOLDEN_DICK -> messageBus.publish(FoundGoldenDickEvent(callbackQuery.message().chat().id(), callbackQuery.from().id()))
        }
        val roundFinished = gameEngine.tryFinishRound(gameId)
        if (roundFinished) {
            val game = gameEngine.getGame(gameId)
            val currentRound = gameEngine.getCurrentRound(game, gameId)
            if (game.firstPlayer.score >= 3 || game.secondPlayer!!.score >= 3) {
                handleFinishGame(callbackQuery, game, currentRound)
                return
            }
            executeSafe(EditMessageText(callbackQuery.message().chat().id(), callbackQuery.message().messageId(), """
                Дуэль. Раунд ${currentRound.order}
                
                ${game.firstPlayer.firstName} - ${game.firstPlayer.score}/3
                ${game.secondPlayer.firstName} - ${game.secondPlayer.score}/3
                
                ${game.firstPlayer.firstName} нашол ${currentRound.entitiesMap[currentRound.firstUserCoordinates!!.first][currentRound.firstUserCoordinates.second]}
                ${game.secondPlayer.firstName} нашол ${currentRound.entitiesMap[currentRound.secondUserCoordinates!!.first][currentRound.secondUserCoordinates.second]}
            """.trimIndent()).replyMarkup(InlineKeyboardMarkup().apply {
                currentRound.entitiesMap.forEach {
                    addRow(*it.map { InlineKeyboardButton(it.value).callbackData("placeholder") }.toTypedArray())
                }
            }))
            executorService.scheduledExecutor.schedule({
                gameEngine.newRound(gameId)
                sendNewRound(callbackQuery, gameEngine.getGame(gameId))
            }, 4, TimeUnit.SECONDS)
        }
    }

    private fun handleDraw(callbackQuery: CallbackQuery, game: Game, currentRound: Round) {
        executeSafe(EditMessageText(callbackQuery.message().chat().id(), callbackQuery.message().messageId(), """
                Дуэль. Раунд ${currentRound.order}
                
                ${game.firstPlayer.firstName} - ${game.firstPlayer.score}/3
                ${game.secondPlayer!!.firstName} - ${game.secondPlayer.score}/3
                
                Нихуя себе! У нас тут ничья!
            """.trimIndent()).replyMarkup(InlineKeyboardMarkup().apply {
            currentRound.entitiesMap.forEach {
                addRow(*it.map { InlineKeyboardButton(it.value).callbackData("placeholder") }.toTypedArray())
            }
        }))
        messageBus.publish(GameFinishedDrawEvent(callbackQuery.message().chat().id(), game.firstPlayer.chatId, game.secondPlayer.chatId))
    }

    private fun handleFinishGame(callbackQuery: CallbackQuery, game: Game, currentRound: Round) {
        if (game.firstPlayer.score == game.secondPlayer!!.score && game.firstPlayer.score >= 3) {
            handleDraw(callbackQuery, game, currentRound);
            return;
        }
        val (winner, loser) = if (game.firstPlayer.score >= 3 && game.firstPlayer.score > game.secondPlayer.score) {
            game.firstPlayer to game.secondPlayer!!
        } else {
            game.secondPlayer!! to game.firstPlayer
        }
        executeSafe(EditMessageText(callbackQuery.message().chat().id(), callbackQuery.message().messageId(), """
                Дуэль. Раунд ${currentRound.order}
                
                ${game.firstPlayer.firstName} - ${game.firstPlayer.score}/3
                ${game.secondPlayer.firstName} - ${game.secondPlayer.score}/3
                
                Победитель - ${winner.firstName}
            """.trimIndent()).replyMarkup(InlineKeyboardMarkup().apply {
            currentRound.entitiesMap.forEach {
                addRow(*it.map { InlineKeyboardButton(it.value).callbackData("placeholder") }.toTypedArray())
            }
        }))
        messageBus.publish(GameFinishedEvent(callbackQuery.message().chat().id(), winner.chatId, loser.chatId))
    }

    private fun isGroup(update: Update): Boolean {
        val chat = update.message()?.chat() ?: update.callbackQuery()?.message()?.chat()
        val type = chat?.type() ?: return false
        return when (type) {
            Chat.Type.group, Chat.Type.supergroup, Chat.Type.channel -> true
            else -> false
        }
    }

    fun retrieveGameId(message: Message): String {
        return """
            ${message.messageId()}${message.chat().id()}
        """.trimIndent()
    }

    companion object : KLogging()

}
