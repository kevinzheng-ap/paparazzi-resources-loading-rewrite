package app.cash.paparazzi.res

/**
 * A marker for classes that require some work done for cleaning up.
 * To do that,
 *
 *  * implement this interface. Please avoid using lambdas or method references because each Disposable instance needs identity to be stored in the Disposer hierarchy correctly
 *  * override the [.dispose] method in your implementation and place your cleanup logic there
 *  * register the instance in [Disposer]
 *
 * After that, when the parent [Disposable] object is disposed (e.g., the project is closed or a window hidden), the [.dispose] method in your implementation will be called automatically by the platform.
 *
 *
 * As a general policy, you shouldn't call the [.dispose] method directly,
 * Instead, register your object in the [Disposer] hierarchy of disposable objects via
 * [Disposer.register] to be automatically disposed along with the the parent object.
 *
 *
 *
 * If you're 100% sure that you should control your object's disposal manually,
 * do not call the [.dispose] method either.
 * Use [Disposer.dispose] instead, since there might be objects registered in the chain
 * that need to be cleaned up before your object.
 *
 * @see com.intellij.openapi.util.CheckedDisposable
 * See [Disposer and Disposable](https://www.jetbrains.org/intellij/sdk/docs/basics/disposers.html) in SDK Docs.
 *
 * @see Disposer
 */
// do not use lambda as a Disposable implementation, because each Disposable instance needs identity to be stored in Disposer hierarchy correctly
interface Disposable {
  /**
   * Usually not invoked directly, see class javadoc.
   */
  fun dispose()
  interface Default : Disposable {
    override fun dispose() {}
  }

  interface Parent : Disposable {
    /**
     * This method is called before [.dispose]
     */
    fun beforeTreeDispose()
  }
}
