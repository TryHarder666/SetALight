def setalight_com(connection):
    import cv2
    import time
    import numpy as np
    import smbus
    import math
    import subprocess
    send = connection

    #Code der die Lichtstaerke ausliest:

    # Get I2C bus
    bus = smbus.SMBus(1)

    # TSL2561 address, 0x39(57)
    # Select control register, 0x00(00) with command register, 0x80(128)
    #       0x03(03)    Power ON mode
    bus.write_byte_data(0x39, 0x00 | 0x80, 0x03)
    # TSL2561 address, 0x39(57)
    # Select timing register, 0x01(01) with command register, 0x80(128)
    #       0x02(02)    Nominal integration time = 402ms
    bus.write_byte_data(0x39, 0x01 | 0x80, 0x02)

    time.sleep(0.5)
        
    # Read data back from 0x0C(12) with command register, 0x80(128), 2 bytes
    # ch0 LSB, ch0 MSB
    data = bus.read_i2c_block_data(0x39, 0x0C | 0x80, 2)

    # Read data back from 0x0E(14) with command register, 0x80(128), 2 bytes
    # ch1 LSB, ch1 MSB
    data1 = bus.read_i2c_block_data(0x39, 0x0E | 0x80, 2)

    # Convert the data
    ch0 = data[1] * 256 + data[0]
    ch1 = data1[1] * 256 + data1[0]
    
    #Faktorberechnung zur Anpassung der gemessenen Werte:
    faktor=(ch0 - ch1) / 2000
    lux = ch0 - ch1

    subprocess.run("/home/pi/capture.sh") #macht das Foto

    img = cv2.imread('/home/pi/webcam/kugel.jpg') #oeffnet das Foto
    img = cv2.medianBlur(img,5) #macht das Foto unscharf
    cimg = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY) #macht das Foto schwarzweiss
           
    circles = cv2.HoughCircles(cimg,cv2.HOUGH_GRADIENT,1,20,param1=50,param2=30,minRadius=0,maxRadius=0) #erkennt den Kreis
    
    circles = np.uint16(np.around(circles))
    first = circles[0][0]

    #definieren der messpunkte
    X,Y,R = first[0],first[1],first[2]
    messpunkt1 = img[X-R//2,Y]
    messpunkt2 = img[X+R//2,Y]
    ky = Y-int(round(R*math.sin(math.pi/4),0))
    kx = X+int(round(R*math.cos(math.pi/4),0))
    messpunkt3 = img[kx-1,ky+1]

    #berechnen der helligkeit
    helligkeit1 = '{:03d}'.format(int(round((messpunkt1[0]*0.11+messpunkt1[1]*0.59+messpunkt1[2]*0.3)*faktor)))
    helligkeit2 = '{:03d}'.format(int(round((messpunkt2[0]*0.11+messpunkt2[1]*0.59+messpunkt2[2]*0.3)*faktor)))
    helligkeit3 = '{:03d}'.format(int(round((messpunkt3[0]*0.11+messpunkt3[1]*0.59+messpunkt3[2]*0.3)*faktor)))       
    helligkeitsstring = str(helligkeit1)+str(helligkeit2)+str(helligkeit3)
    
    #Uebertragung an den Arduino:
    send.write(helligkeitsstring.encode(encoding='UTF-8'))

    print(lux)
    cv2.circle(img,(first[0],first[1]),first[2],(0,255,0),2) #zeichnet den Kreis
    cv2.rectangle(img,(X-R//2,Y),(X-R//2,Y),(0,0,255),3)
    cv2.rectangle(img,(X+R//2,Y),(X+R//2,Y),(0,0,255),3)
    cv2.rectangle(img,(kx-1,ky+1),(kx-1,ky+1),(0,0,255),3)
    cv2.imwrite('/home/pi/webcam/kreis.jpg',img) #speichert das Bild mit dem Kreis
        
    return 0