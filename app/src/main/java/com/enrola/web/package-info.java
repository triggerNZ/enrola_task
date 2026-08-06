/**
 * The HTTP surface: routes, request and response shapes, and the mapping from failures to
 * status codes. Holds no chat logic -- every endpoint delegates to
 * {@link com.enrola.chat.ChatService}.
 *
 * <p>Depends on {@code chat}, and on {@code agent} only for the exception it answers 502 with.
 * Nothing depends on this package.
 */
package com.enrola.web;
