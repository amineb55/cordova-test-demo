package com.actionverite.live.domain.model

/**
 * Self-declared gender. Used only as a soft signal to balance rooms
 * ("équilibrer hommes/femmes lorsque possible"); [UNSPECIFIED] users are never
 * penalized and simply don't count toward balancing.
 */
enum class Gender { MALE, FEMALE, UNSPECIFIED }
