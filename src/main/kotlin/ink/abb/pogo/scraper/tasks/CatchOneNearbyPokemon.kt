/**
 * Pokemon Go Bot  Copyright (C) 2016  PokemonGoBot-authors (see authors.md for more information)
 * This program comes with ABSOLUTELY NO WARRANTY;
 * This is free software, and you are welcome to redistribute it under certain conditions.
 *
 * For more information, refer to the LICENSE file in this repositories root directory
 */

package ink.abb.pogo.scraper.tasks

import POGOProtos.Networking.Responses.CatchPokemonResponseOuterClass.CatchPokemonResponse
import POGOProtos.Networking.Responses.EncounterResponseOuterClass
import POGOProtos.Networking.Responses.EncounterResponseOuterClass.EncounterResponse.Status
import ink.abb.pogo.scraper.Bot
import ink.abb.pogo.scraper.Context
import ink.abb.pogo.scraper.Settings
import ink.abb.pogo.scraper.Task
import ink.abb.pogo.scraper.util.Log
import ink.abb.pogo.scraper.util.inventory.hasPokeballs
import ink.abb.pogo.scraper.util.pokemon.catch
import ink.abb.pogo.scraper.util.pokemon.getIvPercentage
import ink.abb.pogo.scraper.util.pokemon.getStatsFormatted
import ink.abb.pogo.scraper.util.pokemon.shouldTransfer

class CatchOneNearbyPokemon : Task {
    override fun run(bot: Bot, ctx: Context, settings: Settings) {
        val pokemon = ctx.api.map.catchablePokemon.filter { !ctx.blacklistedEncounters.contains(it.encounterId) }

        val hasPokeballs = ctx.api.inventories.itemBag.hasPokeballs()

        if (!hasPokeballs) {
            return
        }

        if (pokemon.isNotEmpty()) {
            val catchablePokemon = pokemon.first()
            if (settings.obligatoryTransfer.contains(catchablePokemon.pokemonId.name) && settings.desiredCatchProbabilityUnwanted == -1.0) {
                ctx.blacklistedEncounters.add(catchablePokemon.encounterId)
                Log.normal("Found pokemon ${catchablePokemon.pokemonId}; blacklisting because it's unwanted")
                return
            }
            Log.green("Found pokemon ${catchablePokemon.pokemonId}")
            ctx.api.setLocation(ctx.lat.get(), ctx.lng.get(), 0.0)

            val encounterResult = catchablePokemon.encounterPokemon()
            if (encounterResult.wasSuccessful()) {
                Log.green("Encountered pokemon ${catchablePokemon.pokemonId} " +
                        "with CP ${encounterResult.wildPokemon.pokemonData.cp} and IV ${encounterResult.wildPokemon.pokemonData.getIvPercentage()}%")
                val (shouldRelease, reason) = encounterResult.wildPokemon.pokemonData.shouldTransfer(settings)
                val desiredCatchProbability = if (shouldRelease) {
                    Log.yellow("Using desired_catch_probability_unwanted because $reason")
                    settings.desiredCatchProbabilityUnwanted
                } else {
                    settings.desiredCatchProbability
                }
                if (desiredCatchProbability == -1.0) {
                    ctx.blacklistedEncounters.add(catchablePokemon.encounterId)
                    Log.normal("CP/IV of encountered pokemon ${catchablePokemon.pokemonId} turns out to be too low; blacklisting encounter")
                    return
                }
                val result = catchablePokemon.catch(
                        encounterResult.captureProbability,
                        ctx.api.inventories.itemBag,
                        desiredCatchProbability,
                        settings.alwaysCurve,
                        !settings.neverUseBerries,
                        -1)

                if (result == null) {
                    // prevent trying it in the next iteration
                    ctx.blacklistedEncounters.add(catchablePokemon.encounterId)
                    Log.red("No Pokeballs in your inventory; blacklisting Pokemon")
                    return
                }

                if (result.status == CatchPokemonResponse.CatchStatus.CATCH_SUCCESS) {
                    ctx.pokemonStats.first.andIncrement
                    val iv = (encounterResult.wildPokemon.pokemonData.individualAttack + encounterResult.wildPokemon.pokemonData.individualDefense + encounterResult.wildPokemon.pokemonData.individualStamina) * 100 / 45
                    var message = "Caught a ${catchablePokemon.pokemonId} " +
                            "with CP ${encounterResult.wildPokemon.pokemonData.cp} and IV $iv%"
                    message += "\r\n ${encounterResult.wildPokemon.pokemonData.getStatsFormatted()}"
                    if (settings.shouldDisplayPokemonCatchRewards)
                        message += ": [${result.xpList.sum()}x XP, ${result.candyList.sum()}x " +
                                "Candy, ${result.stardustList.sum()}x Stardust]"
                    Log.cyan(message)

                } else
                    Log.red("Capture of ${catchablePokemon.pokemonId} failed with status : ${result.status}")
            } else {
                Log.red("Encounter failed with result: ${encounterResult.status}")
                if (encounterResult.status == Status.POKEMON_INVENTORY_FULL) {
                    Log.red("Disabling catching of Pokemon")
                    settings.shouldCatchPokemons = false
                }
            }
        }
    }
}

    fun getPreferredBall(pokemon: CatchablePokemon, ctx: Context, settings: Settings) : ItemId? {
        val pokemonName = pokemon.pokemonId.name
        var preferred = settings.preferredBall;

        if(settings.masterBallPrefOverride.contains(pokemonName))
            preferred = ItemId.ITEM_MASTER_BALL
        else if (settings.ultraBallPrefOverride.contains(pokemonName))
            preferred = ItemId.ITEM_ULTRA_BALL
        else if (settings.greatBallPrefOverride.contains(pokemonName))
            preferred = ItemId.ITEM_GREAT_BALL

        return nextBallInOrder(preferred, ctx, settings)
    }

    fun nextBallInOrder(preferred: ItemId, ctx: Context, settings: Settings) : ItemId? {
        var ballSize = settings.pokeballItems.keys.size
        var preferredIdx = settings.pokeballItems.keys.indexOf(preferred)
        var currentIdx = preferredIdx;

        do {
            var item = ctx.api.inventories.itemBag.getItem(settings.pokeballItems.keys.elementAt(currentIdx))
            if (item != null && item.count > 0)
                return item.itemId;

            //Wrap around if we can't find what we need in the order of priority
            currentIdx = (++currentIdx % ballSize);
        } while(currentIdx != preferredIdx)

        return null;
    }
}