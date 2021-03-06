package ru.darkkeks.trackyoursheet.telegram

import com.fasterxml.jackson.annotation.JsonIgnoreType
import kotlin.reflect.KClass
import kotlin.reflect.full.cast


abstract class HandlerResult

class HandlerResultPassThrough : HandlerResult()

class HandlerResultSuccess : HandlerResult()

typealias HandlerBlock<C> = suspend C.() -> HandlerResult

class Handler<T : BaseContext>(val block: HandlerBlock<T>) {
    suspend fun handle(context: T) = context.block()
}

class HandlerList {
    class HandlerHolder<T : BaseContext>(private val klass: KClass<T>, private val handler: Handler<T>) {
        suspend fun handle(context: BaseContext) = handler.handle(klass.cast(context))
    }

    private var fallback : Handler<BaseContext>? = null
    private val handlers = mutableMapOf<KClass<*>, MutableList<HandlerHolder<*>>>()

    fun addFallback(handler: Handler<BaseContext>) {
        fallback = handler
    }

    fun <T : BaseContext> addHandler(klass: KClass<T>, handler: Handler<T>) {
        handlers.computeIfAbsent(klass) { mutableListOf() }.add(HandlerHolder(klass, handler))
    }

    suspend fun <T : BaseContext> handle(context: T): HandlerResult {
        val handlerList = handlers[context::class]
        if (handlerList != null) {
            for (handler in handlerList) {
                val result = handler.handle(context)
                if (result !is HandlerResultPassThrough) {
                    return result
                }
            }
        }
        return fallback?.block?.invoke(context) ?: HandlerResultPassThrough()
    }
}

class HandlerListBuilder {
    private val result = HandlerList()

    private inline fun <reified T: BaseContext> add(handler: Handler<T>) {
        result.addHandler(T::class, handler)
    }

    fun build() = result

    fun fallbackHandler(block: HandlerBlock<BaseContext>) = result.addFallback(Handler(block))

    fun fallback(block: suspend BaseContext.() -> Unit) = fallbackHandler {
        block()
        HandlerResultSuccess()
    }

    fun onEnterHandler(block: HandlerBlock<EnterStateContext>) = add(Handler(block))

    fun onEnter(block: suspend EnterStateContext.() -> Unit) = onEnterHandler {
        block()
        HandlerResultSuccess()
    }

    fun textHandler(block: HandlerBlock<NewMessageContext>)= add(Handler(block))

    fun text(block: suspend NewMessageContext.() -> Unit) = textHandler {
        block()
        HandlerResultSuccess()
    }

    fun commandHandler(block: HandlerBlock<CommandContext>) = add(Handler(block))

    fun commandHandler(vararg command: String, block: HandlerBlock<CommandContext>) = add(Handler<CommandContext> {
        if (this.command in command) {
            this.block()
        } else {
            HandlerResultPassThrough()
        }
    })

    fun command(vararg command: String, block: suspend CommandContext.() -> Unit) = commandHandler(*command) {
        block()
        HandlerResultSuccess()
    }

    fun anyCallbackHandler(block: HandlerBlock<CallbackButtonContext<*>>) {
        add(Handler<CallbackButtonContext<*>> {
            block()
        })
    }

    fun anyCallback(block: suspend CallbackButtonContext<*>.() -> Unit) = anyCallbackHandler {
        block()
        HandlerResultSuccess()
    }

    fun <T : CallbackButton> callbackHandler(klass: KClass<T>, block: HandlerBlock<CallbackButtonContext<T>>) {
        add(Handler<CallbackButtonContext<T>> {
            if (klass.isInstance(button)) {
                block()
            } else {
                HandlerResultPassThrough()
            }
        })
    }

    inline fun <reified T : CallbackButton> callbackHandler(noinline block: HandlerBlock<CallbackButtonContext<T>>) {
        callbackHandler(T::class, block)
    }

    inline fun <reified T : CallbackButton> callback(noinline block: suspend CallbackButtonContext<T>.() -> Unit) = callbackHandler<T> {
        block()
        HandlerResultSuccess()
    }
}

fun buildHandlerList(block: HandlerListBuilder.() -> Unit): HandlerList {
    val builder = HandlerListBuilder()
    builder.block()
    return builder.build()
}