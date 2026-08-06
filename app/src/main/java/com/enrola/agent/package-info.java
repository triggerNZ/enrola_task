/**
 * How the application talks to the model: which model, what standing instructions go with a
 * call, how much history it carries, and what is committed once an answer comes back. Prompts,
 * tools and multi-step workflow belong here as they arrive.
 *
 * <p>Deliberately depends on nothing else in {@code com.enrola}. It works through langchain4j's
 * {@link dev.langchain4j.store.memory.chat.ChatMemoryStore} rather than the Postgres store
 * behind it, so agent design can be exercised without a database -- see {@code ChatAgentTest}.
 *
 * <p>What the model can <em>do</em> lives in {@link com.enrola.agent.tools}. This package holds
 * how it talks: the model, the prompts, the memory window, and the shape of a message.
 */
package com.enrola.agent;
