/**
 * Conversations as records, and the Postgres they live in: which conversation a turn belongs
 * to, what it is called, when it was last used, and the transcript it accumulates.
 *
 * <p>{@link com.enrola.chat.ChatService} is the way in. The exchange with the model is not here
 * -- it belongs to {@link com.enrola.agent.ChatAgent}, which this package calls and the web
 * layer never touches directly.
 */
package com.enrola.chat;
