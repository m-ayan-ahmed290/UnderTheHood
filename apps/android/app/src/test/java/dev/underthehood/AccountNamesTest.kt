package dev.underthehood
import org.junit.Assert.*
import org.junit.Test
class AccountNamesTest {
 @Test fun reportedAccountNamesAreAccepted(){for(name in listOf("A.A OneDrive","M. Ayan Drive","mail@example.com","大学 Drive","cloud+backup"))assertEquals(name,AccountNames.normalize(name));assertEquals("A.A OneDrive",AccountNames.normalize("  A.A OneDrive  "))}
 @Test fun unsafeNamesAreRejected(){for(name in listOf("", "-flag", "bad:remote", "bad/name", "bad\\name", "bad\nname")){try{AccountNames.normalize(name);fail("Accepted unsafe name")}catch(_:IllegalArgumentException){}}}
 @Test fun onlyLoopbackAuthorizationLinksAreLaunched(){assertNotNull(AuthorizationLinks.find("NOTICE: http://127.0.0.1:53682/auth?state=fixture"));for(url in listOf("https://evil.example/auth?state=x","http://127.0.0.1.evil.example:99/auth?state=x","http://127.0.0.1:99/delete?state=x"))assertNull(AuthorizationLinks.find(url))}
}
