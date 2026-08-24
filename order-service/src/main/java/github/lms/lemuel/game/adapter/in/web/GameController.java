package github.lms.lemuel.game.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag(name = "Game", description = "게임 페이지 뷰 라우팅")
@Controller
@RequestMapping("/games")
public class GameController {

    @Operation(summary = "바둑 게임 페이지", description = "바둑 페이지 뷰를 반환한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "뷰 반환")
    })
    @GetMapping("/baduk")
    public String baduk() {
        return "baduk";
    }

    @Operation(summary = "오목 게임 페이지", description = "오목 페이지 뷰를 반환한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "뷰 반환")
    })
    @GetMapping("/omok")
    public String omok() {
        return "omok";
    }
}
