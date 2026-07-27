package com.animalcounter.net

/**
 * Probe state for the « Jetson connecté / hors de portée » banner.
 *
 * Shared, no Android dependencies, so it survives the Time sync screen
 * deletion and can be imported by every ViewModel + screen that drives
 * the reachability banner.
 */
enum class ProbeState { Idle, Probing, Reachable, OutOfRange }