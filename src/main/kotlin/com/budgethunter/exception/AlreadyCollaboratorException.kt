package com.budgethunter.exception

/**
 * Thrown when the person being added to a budget is already a member of it. Mapped to HTTP 409 Conflict: it is
 * an [IllegalStateException], which [GlobalExceptionHandler] answers with 409, so the response is the same as
 * before this type existed. Its own type lets callers and tests tell it apart from any other illegal state.
 */
class AlreadyCollaboratorException(email: String, budgetId: Long) :
    IllegalStateException("User $email is already a collaborator on budget $budgetId")
