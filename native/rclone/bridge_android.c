#include <jni.h>
#include <stdlib.h>
#include <string.h>
extern char *UthCall(char*, char*);
extern void UthFree(char*);
JNIEXPORT jbyteArray JNICALL Java_app_underthehood_Engine_nativeCall(JNIEnv *env, jobject obj, jbyteArray method, jbyteArray input) {
 jsize mlen=(*env)->GetArrayLength(env,method), ilen=(*env)->GetArrayLength(env,input);
 char *m=calloc(mlen+1,1), *i=calloc(ilen+1,1);
 if(!m || !i){free(m);free(i);return NULL;}
 (*env)->GetByteArrayRegion(env,method,0,mlen,(jbyte*)m);
 (*env)->GetByteArrayRegion(env,input,0,ilen,(jbyte*)i);
 char *result=UthCall(m,i);free(m);free(i);
 jsize size=strlen(result);jbyteArray out=(*env)->NewByteArray(env,size);
 if(out)(*env)->SetByteArrayRegion(env,out,0,size,(jbyte*)result);
 UthFree(result);return out;
}
