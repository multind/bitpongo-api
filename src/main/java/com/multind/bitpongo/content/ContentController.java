package com.multind.bitpongo.content;

import com.multind.bitpongo.auth.AuthenticatedUser;
import com.multind.bitpongo.common.api.ApiResponse;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("!test") @RequestMapping("/api/content")
public class ContentController {
    private final ContentService service;
    private final AppVersionService versions;
    public ContentController(ContentService service,AppVersionService versions){this.service=service;this.versions=versions;}
    @GetMapping("/app-version") public ApiResponse<AppVersionService.Release> version(@RequestParam String platform,@RequestParam String version,@RequestParam(required=false)Integer build){return ApiResponse.success(versions.check(platform,version,build));}
    public record Query(String query){}
    @GetMapping("/articles") public ApiResponse<ContentService.Page> list(@RequestParam(defaultValue="HELP")String kind,@RequestParam(defaultValue="en-us")String locale,@RequestParam(defaultValue="")String q,@RequestParam(defaultValue="0")int page){return ApiResponse.success(service.list(kind,locale,q,page));}
    @GetMapping("/articles/{slug}") public ApiResponse<ContentService.Article> detail(@PathVariable String slug,@RequestParam(defaultValue="en-us")String locale){return ApiResponse.success(service.detail(slug,locale));}
    @GetMapping("/search-history") public ApiResponse<List<ContentService.Search>> history(@AuthenticationPrincipal AuthenticatedUser user){return ApiResponse.success(service.history(user.id()));}
    @PostMapping("/search-history") public ApiResponse<Void> save(@AuthenticationPrincipal AuthenticatedUser user,@RequestBody Query query){service.saveSearch(user.id(),query.query());return ApiResponse.success(null);}
    @DeleteMapping("/search-history/{id}") public ApiResponse<Void> remove(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable long id){service.remove(user.id(),id);return ApiResponse.success(null);}
    @DeleteMapping("/search-history") public ApiResponse<Void> clear(@AuthenticationPrincipal AuthenticatedUser user){service.clear(user.id());return ApiResponse.success(null);}
}
