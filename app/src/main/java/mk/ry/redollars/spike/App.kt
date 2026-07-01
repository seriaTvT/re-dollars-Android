package mk.ry.redollars.spike

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.GifDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade

class App : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(GifDecoder.Factory())            // animated smilies (.gif)
                add(OkHttpNetworkFetcherFactory())   // reuse OkHttp for image fetches
            }
            .crossfade(true)
            .build()
}
