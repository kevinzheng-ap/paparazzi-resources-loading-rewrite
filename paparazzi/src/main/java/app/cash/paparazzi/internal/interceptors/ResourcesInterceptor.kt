package app.cash.paparazzi.internal.interceptors

import android.content.Context
import android.graphics.Typeface

object ResourcesInterceptor {
  @JvmStatic
  fun intercept(
    context: Context,
    resId: Int
  ): Typeface? {
    return context.resources.getFont(resId)
  }
}
