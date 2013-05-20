package lila

import lila.db.Tube

package object friend extends PackageObject with WithPlay {

  private[friend] type ID = String

  object tube {

    private[friend] implicit lazy val friendTube =
      Friend.tube inColl Env.current.friendColl

    private[friend] implicit lazy val requestTube =
      Request.tube inColl Env.current.requestColl
  }
}
