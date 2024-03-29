package com.vdsirotkin.telegram.dickfind2bot.engine

import com.vdsirotkin.telegram.dickfind2bot.BotConfig
import org.springframework.stereotype.Component
import kotlin.random.Random

@Component
class MapGenerator(
        val botConfig: BotConfig
) {


    fun generateNewMap(): Array<Array<Entity>> {
        val map = Array(3) {
            Array(3) { listOf(Entity.NOTHING, Entity.DICK).random() }
        }
        val shouldHaveGoldenDick = shouldHaveGoldenDick()
        val shouldHaveBomb = shouldHaveBomb()
        val hasAtLeastOneDick = hasAtLeastOneDick(map)
        val hasAtLeastOneNothing = hasAtLeastOneNothing(map)

        if (shouldHaveGoldenDick) {
            populateGoldDickInMap(map)
        }

        if (shouldHaveBomb) {
            populateBombInMap(map)
        }

        if (hasAtLeastOneDick && hasAtLeastOneNothing) {
            return map
        } else if (hasAtLeastOneNothing) {
            populateSimpleDickInMap(map)
        } else {
            populateNothingInMap(map)
        }

        return map
    }

    private fun populateBombInMap(map: Array<Array<Entity>>) {
        val row: Int = (0..2).random()
        val cell: Int = (0..2).random()

        map[row][cell] = Entity.BOMB
    }


    private fun shouldHaveGoldenDick(): Boolean {
        return Random.Default.nextDouble() <= botConfig.goldenDickChance
    }

    private fun shouldHaveBomb(): Boolean {
        return Random.Default.nextDouble() <= botConfig.bombChance
    }

    private fun hasAtLeastOneNothing(map: Array<Array<Entity>>): Boolean {
        return map.any { it ->
            it.any { it == Entity.NOTHING }
        }
    }

    private fun hasAtLeastOneDick(map: Array<Array<Entity>>): Boolean {
        return map.any { it ->
            it.any { it == Entity.DICK }
        }
    }

    private fun populateGoldDickInMap(map: Array<Array<Entity>>) {
        val row: Int = (0..2).random()
        val cell: Int = (0..2).random()

        map[row][cell] = Entity.GOLDEN_DICK
    }

    private fun populateSimpleDickInMap(map: Array<Array<Entity>>) {
        val row: Int = (0..2).random()
        val cell: Int = (0..2).random()

        if (map[row][cell] == Entity.GOLDEN_DICK || map[row][cell] == Entity.BOMB) {
            return populateSimpleDickInMap(map)
        }

        map[row][cell] = Entity.DICK
    }

    private fun populateNothingInMap(map: Array<Array<Entity>>) {
        val row: Int = (0..2).random()
        val cell: Int = (0..2).random()

        if (map[row][cell] == Entity.GOLDEN_DICK) {
            return populateNothingInMap(map)
        }

        map[row][cell] = Entity.NOTHING
    }

}
