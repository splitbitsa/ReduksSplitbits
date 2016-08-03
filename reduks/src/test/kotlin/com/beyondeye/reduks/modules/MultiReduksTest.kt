package com.beyondeye.reduks.modules

import org.assertj.core.api.Assertions.assertThat
import com.beyondeye.reduks.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * tests for MultiReduks
 * Created by daely on 8/1/2016.
 */
class MultiReduksTest {
    @Before
    fun setUp() {
        nStateChanges1 = 0
        nStateChanges2 = 0
        nStateChangeCalls1 = 0
        nStateChangeCalls2 = 0
    }

    data class TestState1(val lastActionType: String = "")
    data class TestState2(val lastActionType: String = "")
    data class TestAction1(val type: String)
    data class TestAction2(val type: String)

    val reducer1 = Reducer<TestState1> { state, action ->
        when (action) {
            is TestAction1 -> state.copy(lastActionType = action.type)
            else -> state
        }
    }
    val reducer2 = Reducer<TestState2> { state, action ->
        when (action) {
            is TestAction2 -> state.copy(lastActionType = action.type)
            else -> state
        }
    }
    var nStateChanges1 = 0
    var nStateChangeCalls1 = 0
    val m1 = ReduksModuleDef<TestState1>(
            storeFactory = SimpleStore.Factory<TestState1>(),
            initialState = TestState1(),
            startAction = TestAction1("start1"),
            stateReducer = reducer1,
            subscriberBuilder = StoreSubscriberBuilder { store ->
                val selector = SelectorBuilder<TestState1>()
                val selForLastAction = selector.withSingleField { this.lastActionType }
                StoreSubscriber { state ->
                    ++nStateChangeCalls1
                    selForLastAction.onChangeIn(state) {
                        ++nStateChanges1
                    }
                }
            }
    )
    var nStateChanges2 = 0
    var nStateChangeCalls2 = 0
    val m2 = ReduksModuleDef<TestState2>(
            storeFactory = SimpleStore.Factory<TestState2>(),
            initialState = TestState2(),
            startAction = TestAction2("start2"),
            stateReducer = reducer2,
            subscriberBuilder = StoreSubscriberBuilder { store ->
                val selector = SelectorBuilder<TestState2>()
                val selForLastAction = selector.withSingleField { this.lastActionType }
                StoreSubscriber { state ->
                    ++nStateChangeCalls2
                    selForLastAction.onChangeIn(state) {
                        ++nStateChanges2
                    }
                }
            }
    )

    val ctx1 = ReduksContext("m1")
    val ctx2 = ReduksContext("m2")
    @Test
    fun test_multireduks2_correctly_initialized() {
        val mr = MultiReduks.buildFromModules(m1, ctx1, m2, ctx2)
        assertThat(mr.store.state.s1.lastActionType).isEqualTo("start1") //check that start action dispatched
        assertThat(nStateChanges1).isEqualTo(1) //start action
        assertThat(nStateChangeCalls1).isEqualTo(1) //start action
        assertThat(mr.store.state.s2.lastActionType).isEqualTo("start2") //check that start action dispatched
        assertThat(nStateChanges2).isEqualTo(1) //start action
        assertThat(nStateChangeCalls2).isEqualTo(1) //start action
    }

    @Test
    fun test_multireduks2_dispatch() {
        //----GIVEN
        val mr = MultiReduks.buildFromModules(m1, ctx1, m2, ctx2)
        //-----WHEN
        mr.dispatch(ActionWithContext(TestAction1("1"), ctx1))
        assertThat(mr.store.state.s1.lastActionType).isEqualTo("1")
        assertThat(mr.store.state.s2.lastActionType).isEqualTo("start2")
        //-----THEN
        assertThat(nStateChanges1).isEqualTo(2) //start action and one additional dispatch
        assertThat(nStateChangeCalls1).isEqualTo(2) //start action and one additional dispatch
        assertThat(nStateChanges2).isEqualTo(1) //start action
        assertThat(nStateChangeCalls2).isEqualTo(1) //start action
        //-----AND WHEN
        mr.dispatch(ActionWithContext(TestAction2("2"), ctx2))
        assertThat(mr.store.state.s1.lastActionType).isEqualTo("1")
        assertThat(mr.store.state.s2.lastActionType).isEqualTo("2")
        //-----THEN
        assertThat(nStateChanges1).isEqualTo(2) //start action and one additional dispatch
        assertThat(nStateChangeCalls1).isEqualTo(2) //start action and one additional dispatch
        assertThat(nStateChanges2).isEqualTo(2) //start action and one additional dispatch
        assertThat(nStateChangeCalls2).isEqualTo(2) //start action and one additional dispatch
        //-----AND WHEN
        mr.dispatch(ActionWithContext("unknown action", ctx2))
        assertThat(mr.store.state.s1.lastActionType).isEqualTo("1")
        assertThat(mr.store.state.s2.lastActionType).isEqualTo("2")
        //-----THEN
        assertThat(nStateChanges1).isEqualTo(2) //start action and one additional dispatch
        assertThat(nStateChangeCalls1).isEqualTo(2) //start action and one additional dispatch
        assertThat(nStateChanges2).isEqualTo(2) //start action and one additional dispatch
        assertThat(nStateChangeCalls2).isEqualTo(3) //start action and one additional dispatch+unknown actions

    }


}