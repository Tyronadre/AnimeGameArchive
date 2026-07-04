package de.tyro.genshinapp.repository

import de.tyro.genshinapp.entity.Material
import org.springframework.data.repository.CrudRepository

interface MaterialRepository: CrudRepository<Material, String> {

}