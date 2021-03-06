/*
 * Copyright (c) 2018  STMicroelectronics – All rights reserved
 * The STMicroelectronics corporate logo is a trademark of STMicroelectronics
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this list of conditions
 *    and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice, this list of
 *    conditions and the following disclaimer in the documentation and/or other materials provided
 *    with the distribution.
 *
 * - Neither the name nor trademarks of STMicroelectronics International N.V. nor any other
 *    STMicroelectronics company nor the names of its contributors may be used to endorse or
 *    promote products derived from this software without specific prior written permission.
 *
 * - All of the icons, pictures, logos and other images that are provided with the source code
 *    in a directory whose title begins with st_images may only be used for internal purposes and
 *    shall not be redistributed to any third party or modified in any way.
 *
 * - Any redistributions in binary form shall not include the capability to display any of the
 *    icons, pictures, logos and other images that are provided with the source code in a directory
 *    whose title begins with st_images.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY
 * AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
 * OF SUCH DAMAGE.
 */
package com.st.smarTag.cloud.provider.IBMWatson

import android.content.Context
import android.util.Log
import com.st.smarTag.cloud.provider.CloudProvider
import org.eclipse.paho.android.service.MqttAndroidClient
import androidx.annotation.RawRes
import com.google.android.gms.common.GooglePlayServicesNotAvailableException
import com.google.android.gms.common.GooglePlayServicesRepairableException
import com.google.android.gms.security.ProviderInstaller
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.st.smarTag.cloud.R
import com.st.smarTag.cloud.provider.MqttActionAdapter
import com.st.smarTag.cloud.provider.json.*
import com.st.smartaglib.model.*
import org.eclipse.paho.client.mqttv3.*
import java.security.KeyStore
import java.util.*
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

internal class IBMWatsonProvider(private val context: Context,private val settings: IBMWatsonParameters):CloudProvider{


    private class IBMWatsonProvider(val client: MqttAndroidClient) : CloudProvider.Connection

    override fun connect(callback: CloudProvider.ConnectionCallback) {
        val server = settings.connectionUrl

        val client = MqttAndroidClient(context, server,settings.connectionDeviceId)
        val options = MqttConnectOptions().apply {
            isCleanSession=true
            userName="use-token-auth"
            password = settings.authToken
            socketFactory= createSSLSocketFactory(context, R.raw.ibm_watson_ca)
        }
        //is the has leaked ServiceConnection due to a in progress disconnection done during a new connection?
        // https://github.com/eclipse/paho.mqtt.android/issues/313
        client.connect(options,context,object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                callback.onSuccess(IBMWatsonProvider(client))
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                if(exception!=null)
                    callback.onFail(exception)
                client.close()
                client.unregisterResources()
            }

        })
    }

    private val jsonSerializer = GsonBuilder().apply {
            registerTypeAdapter(Date::class.java, DateJsonSerializer())
            registerTypeAdapter(SensorDataSample::class.java, SensorDataSampleJsonAdapter())
            registerTypeAdapter(EventDataSample::class.java, EventDataSampleJsonAdapter())
            registerTypeAdapter(TagExtreme::class.java, TagExtremesJsonAdapter())
            registerTypeAdapter(DataExtreme::class.java, DataExtremesJsonAdapter())
            registerTypeAdapterFactory(SensorDataSampleJsonFactory())
            registerTypeAdapterFactory(EventDataSampleJsonFactory())
            registerTypeAdapterFactory(ExtremeDataJsonFactory())
        }.create()

    override fun uploadSamples(conn: CloudProvider.Connection, samples: List<DataSample>, callback: CloudProvider.PublishCallback) {
        conn.client?.let { mqtt ->
            val json = jsonSerializer.toJsonTree(samples)

            val ibmDeviceInfo = JsonObject()
            ibmDeviceInfo.add("d", json)
            Log.d("service",ibmDeviceInfo.toString())
            val message = MqttMessage(ibmDeviceInfo.toString().toByteArray(Charsets.UTF_8)).apply {
                qos=0
            }
            mqtt.publish("iot-2/evt/${settings.eventsTopic}/fmt/json",message,null, MqttActionAdapter(callback))
        }
    }


    override fun uploadExtreme(conn: CloudProvider.Connection, extremeData: TagExtreme,callback: CloudProvider.PublishCallback) {
        conn.client?.let { mqtt ->
            val json = jsonSerializer.toJsonTree(extremeData)

            val ibmDeviceInfo = JsonObject()
            ibmDeviceInfo.add("d", json)
            Log.d("service",ibmDeviceInfo.toString())
            val message = MqttMessage(ibmDeviceInfo.toString().toByteArray(Charsets.UTF_8)).apply {
                qos=0
            }
            mqtt.publish("iot-2/evt/${settings.eventsTopic}/fmt/json",message,null,MqttActionAdapter(callback))
        }
    }

    override fun disconnect(connection: CloudProvider.Connection) {

        connection.client?.apply {
            close()
            unregisterResources()
        }
    }

    private val CloudProvider.Connection.client
        get() = (this as? com.st.smarTag.cloud.provider.IBMWatson.IBMWatsonProvider.IBMWatsonProvider)?.client

    companion object {

        private const val SSL_PROTOCOL = "TLSv1.2"

        private fun updateSecurityProvider(context: Context){
            try {
                ProviderInstaller.installIfNeeded(context)
            } catch (e: GooglePlayServicesRepairableException) {
                Log.e(IBMWatsonProvider::class.java.name,"Error installing provider",e)
            } catch (e: GooglePlayServicesNotAvailableException) {
                Log.e(IBMWatsonProvider::class.java.name,"Error installing provider",e)
            }
        }

        fun createSSLSocketFactory(context: Context, @RawRes certificateID: Int): SocketFactory {
            updateSecurityProvider(context)

            val ks = KeyStore.getInstance("bks")
            ks.load(context.resources.openRawResource(certificateID), "password".toCharArray())

            val tmf = TrustManagerFactory.getInstance("X509")
            tmf.init(ks)

            val sslContext = SSLContext.getInstance(SSL_PROTOCOL)
            sslContext.init(null, tmf.trustManagers, null)
            return sslContext.socketFactory
        }
    }

}