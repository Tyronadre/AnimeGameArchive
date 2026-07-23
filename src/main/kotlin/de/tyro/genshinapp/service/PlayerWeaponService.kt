package de.tyro.genshinapp.service

import de.tyro.genshinapp.entity.PlayerWeaponInstance
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.PlayerWeapon
import de.tyro.genshinapp.repository.GameWeaponRepository
import de.tyro.genshinapp.repository.PlayerWeaponInstanceRepository
import de.tyro.genshinapp.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PlayerWeaponService(
    private val weaponCatalogService: WeaponCatalogService,
    private val weaponRepository: GameWeaponRepository,
    private val playerWeaponRepository: PlayerWeaponInstanceRepository,
    private val userRepository: UserRepository,
) {
    @Transactional(readOnly = true)
    fun findAll(userId: Long): List<PlayerWeapon> =
        playerWeaponRepository.findAllByUser_IdOrderByImportPositionAscIdAsc(userId)
            .map { instance ->
                PlayerWeapon(
                    key = instance.weapon.key,
                    level = instance.level,
                    ascension = instance.ascension,
                    refinement = instance.refinement,
                    location = instance.location,
                    locked = instance.locked,
                )
            }

    @Transactional
    fun replaceAll(userId: Long, weapons: Collection<PlayerWeapon>) {
        require(userId > 0) { "Invalid user id" }
        val user = userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("Unknown user id") }
        val normalizedWeapons = weapons.map { weapon ->
            weapon.copy(key = GoodKeyNormalizer.normalize(weapon.key))
        }
        weaponCatalogService.ensureWeapons(normalizedWeapons.map(PlayerWeapon::key))
        val baseWeapons = weaponRepository.findAll()
            .associateBy { weapon -> weapon.key }

        playerWeaponRepository.deleteAllForUser(userId)
        playerWeaponRepository.saveAll(
            normalizedWeapons.mapIndexed { index, weapon ->
                PlayerWeaponInstance().also { instance ->
                    instance.user = user
                    instance.weapon = requireNotNull(baseWeapons[weapon.key]) {
                        "Weapon catalog entry '${weapon.key}' is missing"
                    }
                    instance.importPosition = index
                    instance.level = weapon.level
                    instance.ascension = weapon.ascension
                    instance.refinement = weapon.refinement
                    instance.location = weapon.location
                    instance.locked = weapon.locked
                }
            },
        )
    }
}
