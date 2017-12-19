package com.beyondeye.reduks.experimental.middlewares.saga

import com.beyondeye.reduks.*
import kotlinx.coroutines.experimental.CoroutineStart
import kotlinx.coroutines.experimental.DefaultDispatcher
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.launch
import java.lang.ref.WeakReference
import kotlin.coroutines.experimental.CoroutineContext



class SagaProcessor<S:Any>(
        private val dispatcherActor:SendChannel<Any>
)
{
    class Put(val value:Any)
    class Take<B>(val type:Class<B>)
    class TakeEvery<S:Any,B>(val process: Saga2<S>.(B) -> Any?)
    class SagaFinished(val sm:SagaMiddleWare2<*>, val sagaName: String)

    /**
     * channel for communication between Saga and Saga processor
     */
    val inChannel :Channel<Any> = Channel()
    val outChannel :Channel<Any> = Channel()
    /**
     * channel where processor receive actions dispatched to the store
     */
    suspend fun start(inputActions:ReceiveChannel<Any>) {
        for(a in inChannel) {
            when(a) {
                is Put ->
                    dispatcherActor.send(a.value)
                is Take<*> -> {
//                    launch { //launch aynchronously, to avoid dead locks?
                        for(ia in inputActions) {
                            if(ia::class.java==a.type) {
                                outChannel.send(ia)
                                break
                            }
                        }
//                    }

                }
                is SagaFinished -> {
                    a.sm.sagaProcessorFinished(a.sagaName)
                    return
                }
                else ->  {
                    print("unsupported saga operation: ${a::class.java}")
                }
            }
        }
    }

    fun stop() {
        inChannel.close()
        outChannel.close()
    }
}

class SagaYeldSingle<S:Any>(private val sagaProcessor: SagaProcessor<S>){
    suspend infix fun put(value:Any) {
        yieldSingle(SagaProcessor.Put(value))
    }


    suspend infix fun <B> takeEvery(process: Saga2<S>.(B) -> Any?)
    {
        yieldSingle( SagaProcessor.TakeEvery<S,B>(process))
    }
    //-----------------------------------------------
    suspend fun yieldSingle(value: Any) {
        sagaProcessor.inChannel.send(value)
    }
    suspend fun yieldBackSingle(): Any {
        return sagaProcessor.outChannel.receive()
    }
}
suspend inline fun <reified B> SagaYeldSingle<*>.take():B {
    yieldSingle(SagaProcessor.Take<B>(B::class.java))
    return yieldBackSingle() as B
}
class SagaYeldAll<S:Any>(private val sagaProcessor: SagaProcessor<S>){
    private suspend  fun yieldAll(inputChannel: ReceiveChannel<Any>) {
        for (a in inputChannel) {
            sagaProcessor.inChannel.send(a)
        }
    }
}

class Saga2<S:Any>(sagaProcessor:SagaProcessor<S>) {
    val yieldSingle= SagaYeldSingle(sagaProcessor)
    val yieldAll= SagaYeldAll(sagaProcessor)
}

/**
 * a port of saga middleware
 * It store a (weak) reference to the store, so a distinct instance must be created for each store
 * https://github.com/redux-saga/redux-saga/
 * https://redux-saga.js.org/
 *
 * Created by daely on 12/15/2017.
 */
class SagaMiddleWare2<S:Any>(store_:Store<S>,val sagaContext:CoroutineContext= DefaultDispatcher) : Middleware<S> {
    private val dispatcherActor: SendChannel<Any>
    private val incomingActionsDistributionActor: SendChannel<Any>
    private var childSagas:Map<String,SagaData<S>>
    private val store:WeakReference<Store<S>>
    init {
        store=WeakReference(store_)
        childSagas= mapOf()
        //use an actor for dispatching so that we ensure we preserve action order
        dispatcherActor = actor<Any>(sagaContext) {
            for (a in channel) { //loop over incoming message
                store.get()?.dispatch?.invoke(a)
            }
        }
        //use an actor for distributing incoming actions so we ensure we preserve action order
        //define a channel with unlimited capacity, because we don't want the action dispatcher
        incomingActionsDistributionActor = actor<Any>(sagaContext,Channel.UNLIMITED) {
            for (a in channel) { // iterate over incoming actions
                //distribute incoming actions to sagas
                for (saga in childSagas.values) {
                    saga.inputActionsChannel?.send(a)
                }
            }
        }

    }
    override fun dispatch(store: Store<S>, nextDispatcher:  (Any)->Any, action: Any):Any {
        val res=nextDispatcher(action) //hit the reducers before processing actions in saga middleware!
        //use actor here to make sure that actions are distributed in the right order
        launch(sagaContext) {
            incomingActionsDistributionActor.send(action)
        }
        return res
    }

    private data class SagaData<S:Any>(
            val inputActionsChannel: SendChannel<Any>?,
            val sagaProcessor:SagaProcessor<S>?,
            val sagaJob: Job?)

    fun runSaga(sagaName:String,sagafn: suspend Saga2<S>.() -> Unit) {

        val sagaProcessor=SagaProcessor<S>(dispatcherActor)
        //define the saga processor receive channel, that is used to receive actions from dispatcher
        //to have unlimited buffer, because we don't want to block the dispatcher
        val sagaInputActionsChannel=actor<Any>(sagaContext,Channel.UNLIMITED) {
            sagaProcessor.start(this)
        }

        val saga=Saga2(sagaProcessor)
        //start lazily, so that we have time to insert sagaData in childSagas map
        //because otherwise stopSaga() at the end of the sagaJob will not work
        val sagaJob= launch(sagaContext,start = CoroutineStart.LAZY) {
            saga.sagafn()
            launch(sagaContext){
                sagaFinished(sagaName)
            }
        }
        addSagaData(sagaName, SagaData(sagaInputActionsChannel, sagaProcessor, sagaJob))
        //we are ready to start now
        sagaJob.start()
    }
    private suspend fun sagaFinished(sagaName: String) {
        updateSagaData(sagaName) { finishedSaga->
            if(finishedSaga==null)
                throw Exception("this must not happen")
            launch(sagaContext) {
                finishedSaga.sagaProcessor?.inChannel?.send(SagaProcessor.SagaFinished(this@SagaMiddleWare2, sagaName))
            }
            finishedSaga.copy(sagaJob = null)
        }
    }
    internal fun sagaProcessorFinished(sagaName: String) {
        updateSagaData(sagaName) { sagaData ->
            if(sagaData==null) return@updateSagaData null
            sagaData.inputActionsChannel?.close()
            sagaData.copy(sagaProcessor = null,inputActionsChannel = null)
        }
    }
    fun stopSaga(sagaName:String) {
        val deletedSaga=deleteSagaData(sagaName)
        deletedSaga?.apply {
            sagaJob?.cancel()
            inputActionsChannel?.close()
            sagaProcessor?.stop()
        }
    }
    private fun updateSagaData(sagaName:String,updatefn:(SagaData<S>?)->SagaData<S>?) {
        synchronized(this) {
            val curData=childSagas[sagaName]
            val newChildSagas= childSagas.toMutableMap()
            updatefn(curData)?.let{ newData->
                newChildSagas.put(sagaName,newData)
            }
            childSagas=newChildSagas
        }
    }
    private fun addSagaData(sagaName:String,sagaData:SagaData<S>) {
        synchronized(this) {
            val newChildSagas= childSagas.toMutableMap()
            newChildSagas.put(sagaName,sagaData)
            childSagas=newChildSagas
        }
    }
    private fun deleteSagaData(sagaName:String): SagaData<S>? {
        var deletedSaga:SagaData<S>?=null
        synchronized(this) {
            val newChildSagas= childSagas.toMutableMap()
            deletedSaga=newChildSagas.remove(sagaName)
            childSagas=newChildSagas
        }
        return deletedSaga
    }



}
