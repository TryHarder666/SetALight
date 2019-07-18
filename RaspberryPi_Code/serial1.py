#!/usr/bin/python3 -u
import serial
import time
from setalight import *
from portset import *

serialports = serial_ports()

connection = serial.Serial(serialports[0], 115200)     #stellt die Verbindung zum Arduino her
  
connection.isOpen()
time.sleep(1)

while True:
    try:
        command = connection.readline().decode('UTF-8')
        if "setalight" in command:                      #prüft, ob es sich bei dem eingehenden Serial um den Startbefehl handelt
            setalight_com(connection)                   #startet das Skript
    except:
        pass
        
