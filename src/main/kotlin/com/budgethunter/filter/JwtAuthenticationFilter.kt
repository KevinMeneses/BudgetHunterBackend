package com.budgethunter.filter

import com.budgethunter.repository.UserRepository
import com.budgethunter.util.JwtUtil
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthenticationFilter(
    private val jwtUtil: JwtUtil,
    private val userRepository: UserRepository
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val authHeader = request.getHeader("Authorization")

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response)
            return
        }

        try {
            val token = authHeader.substring(7)
            val email = jwtUtil.extractEmail(token)

            if (SecurityContextHolder.getContext().authentication == null) {
                // Null when the account no longer exists, which ends its sessions at once (account deletion).
                // A token issued before the account's sessions were ended carries a lower version.
                val currentVersion = userRepository.findTokenVersion(email)
                val tokenVersion = jwtUtil.extractTokenVersion(token)

                if (currentVersion != null && tokenVersion == currentVersion && jwtUtil.isTokenValid(token, email)) {
                    // The version travels as the credentials, for the operations that must recheck it under a
                    // lock (UserService.setPassword): a request can pass this filter just before the version rises.
                    val authToken = UsernamePasswordAuthenticationToken(
                        email,
                        tokenVersion,
                        emptyList()
                    )
                    authToken.details = WebAuthenticationDetailsSource().buildDetails(request)
                    SecurityContextHolder.getContext().authentication = authToken
                }
            }
        } catch (e: Exception) {
            // Invalid token - do nothing, let the request proceed unauthenticated
        }

        filterChain.doFilter(request, response)
    }
}
