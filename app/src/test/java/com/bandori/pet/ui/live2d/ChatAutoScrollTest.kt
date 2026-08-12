package com.bandori.pet.ui.live2d

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAutoScrollTest {
    @Test
    fun followsWhenUserWasAtPreviousBottomAndTwoItemsArrive() {
        assertTrue(shouldFollowNewChatContent(previousItemCount = 10, lastVisibleIndex = 9))
    }

    @Test
    fun preservesPositionWhenUserScrolledAwayFromBottom() {
        assertFalse(shouldFollowNewChatContent(previousItemCount = 10, lastVisibleIndex = 5))
    }

    @Test
    fun initialContentStartsAtBottom() {
        assertTrue(shouldFollowNewChatContent(previousItemCount = 0, lastVisibleIndex = -1))
    }
}
