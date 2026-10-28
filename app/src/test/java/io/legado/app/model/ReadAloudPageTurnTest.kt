package io.legado.app.model
import org.junit.Assert.*
import org.junit.Test
class ReadAloudPageTurnTest {
 @Test fun disabledSwitchKeepsPageTurnsBlocked() {
  assertEquals(0,ReadAloudDragSeekSession.pageTurnDirection(false,-200f,0f,10))
 }
 @Test fun horizontalDirectionsUseExistingPageAnimations() {
  assertEquals(1,ReadAloudDragSeekSession.pageTurnDirection(true,-200f,5f,10))
  assertEquals(-1,ReadAloudDragSeekSession.pageTurnDirection(true,200f,5f,10))
 }
 @Test fun verticalSeekingAndSmallMovementsDoNotTurnPages() {
  assertEquals(0,ReadAloudDragSeekSession.pageTurnDirection(true,5f,200f,10))
  assertEquals(0,ReadAloudDragSeekSession.pageTurnDirection(true,5f,1f,10))
 }
}
