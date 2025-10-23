package com.crstlnz

suspend fun main() {
    val api = KuronimeProvider()
    println(api.search("Sono bisque"))
}
