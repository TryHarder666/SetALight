#include <SoftwareSerial.h>
#define BTSerial Serial1
#include <DmxSimple.h>

void setup() {
    Serial.begin(115200);
    BTSerial.begin(115200);
    DmxSimple.usePin(3);
    DmxSimple.maxChannel(9);
}

void loop() {
    if (Serial.available()){                              //leitet die Daten vom seriellen Port an das Bluetooth-Modul weiter
      String messung = Serial.readStringUntil("\n");
      BTSerial.println(messung);
      delay(10);
    }
    if (BTSerial.available()){
    String steuerung = BTSerial.readStringUntil("\n");
      if (isDigit(steuerung.charAt(0))) {                 //testet ob der eingehende String ein DMX Steuerbefehl ist
        int wert1a = (steuerung.substring(0,3)).toInt();  //zerteilt den String in die einzelnen RGB Werte
        int wert1b = (steuerung.substring(3,6)).toInt();
        int wert1c = (steuerung.substring(6,9)).toInt();
        int wert2a = (steuerung.substring(9,12)).toInt();
        int wert2b = (steuerung.substring(12,15)).toInt();
        int wert2c = (steuerung.substring(15,18)).toInt();
        int wert3a = (steuerung.substring(18,21)).toInt();
        int wert3b = (steuerung.substring(21,24)).toInt();
        int wert3c = (steuerung.substring(24,27)).toInt();
        DmxSimple.write(1, wert1a);                        //schreibt die vom Smartphone gesendeten RGB Werte in die DMX Kanäle
        DmxSimple.write(2, wert1b);
        DmxSimple.write(3, wert1c);
        DmxSimple.write(4, wert2a);
        DmxSimple.write(5, wert2b);
        DmxSimple.write(6, wert2c);
        DmxSimple.write(7, wert3a);
        DmxSimple.write(8, wert3b);
        DmxSimple.write(9, wert3c);
      }
      if (isAlpha(steuerung.charAt(0))) {                   //testet ob der eingehende String der Befehl zur Lichtmessung ist
        Serial.println(steuerung);
       delay(10); 
      }     
    }
}
