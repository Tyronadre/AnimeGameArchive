package de.tyro.genshinapp.service.damage.v2

/** Shared field traversal for character, artifact, and weapon modifier expressions. */
fun FormulaModifierV2.bindExpressions(bind: (FormulaExpressionV2) -> FormulaExpressionV2): FormulaModifierV2 {
    fun contribution(value: FormulaContributionV2?) = value?.copy(expression = bind(value.expression))
    return copy(base = base?.let(bind), baseDamageBonus = contribution(baseDamageBonus),
        baseDamageMultiplier = contribution(baseDamageMultiplier), damageBonus = contribution(damageBonus),
        postBonusBase = postBonusBase?.let(bind), elevation = contribution(elevation),
        defenseMitigation = defenseMitigation?.copy(reduction = contribution(defenseMitigation.reduction), ignore = contribution(defenseMitigation.ignore)),
        resistanceMitigation = resistanceMitigation?.copy(reduction = contribution(resistanceMitigation.reduction)),
        crit = crit?.copy(additionalRate = contribution(crit.additionalRate), additionalDamage = contribution(crit.additionalDamage)))
}
