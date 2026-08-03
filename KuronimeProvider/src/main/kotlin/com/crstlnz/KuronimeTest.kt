package com.crstlnz

import com.lagradost.cloudstream3.MainPageRequest

suspend fun main() {
    val api = KuronimeProvider()
    val w = api.mainPage.first()
    val mainPage = api.getMainPage(1, MainPageRequest(w.name, w.data, false))
    println(mainPage)
//    val data = api.search("Sono bisque")
//    println(data)
//
//    val detail = api.load(data?.first()?.url ?: "")
//    println(detail)
//
//    val links = api.loadLinks(detail.episodes.values.first().first().data, false, { data ->
//        println(data)
//    }, { d -> println(d) })

//    val data = api.load("https://kuronime.sbs/anime/kimetsu-no-yaiba-season-2/")
//    println(data)
}
