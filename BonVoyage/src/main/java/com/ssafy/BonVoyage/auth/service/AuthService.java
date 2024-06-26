package com.ssafy.BonVoyage.auth.service;


import com.ssafy.BonVoyage.auth.advice.assertThat.DefaultAssert;
import com.ssafy.BonVoyage.auth.config.security.token.UserPrincipal;
import com.ssafy.BonVoyage.auth.domain.*;
import com.ssafy.BonVoyage.auth.payload.request.auth.ChangePasswordRequest;
import com.ssafy.BonVoyage.auth.payload.request.auth.RefreshTokenRequest;
import com.ssafy.BonVoyage.auth.payload.request.auth.SignInRequest;
import com.ssafy.BonVoyage.auth.payload.request.auth.SignUpRequest;
import com.ssafy.BonVoyage.auth.payload.response.ApiResponse;
import com.ssafy.BonVoyage.auth.payload.response.AuthResponse;
import com.ssafy.BonVoyage.auth.payload.response.Message;
import com.ssafy.BonVoyage.auth.repository.MemberRepository;
import com.ssafy.BonVoyage.auth.repository.TokenRepository;
import com.ssafy.BonVoyage.file.dto.ProfileImageDto;
import com.ssafy.BonVoyage.file.service.ImageService;
import com.ssafy.BonVoyage.file.service.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;


@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final PasswordEncoder passwordEncoder;
    private final CustomTokenProviderService customTokenProviderService;
    private final ImageService imageService;
    private final MemberRepository memberRepository;
    private final TokenRepository tokenRepository;
    private final S3Service s3Service;
    @Value("${cloud.address}")
    private String CLOUD_FRONT_DOMAIN_NAME;

    public ResponseEntity<?> whoAmI(UserPrincipal userPrincipal){
        Optional<Member> member = memberRepository.findById(userPrincipal.getId());
        DefaultAssert.isOptionalPresent(member);
        ApiResponse apiResponse = ApiResponse.builder().check(true).information(member.get()).build();

        return ResponseEntity.ok(apiResponse);
    }

    public ResponseEntity<?> delete(UserPrincipal userPrincipal){
        Optional<Member> member = memberRepository.findById(userPrincipal.getId());
        DefaultAssert.isTrue(member.isPresent(), "유저가 올바르지 않습니다.");

        Optional<Token> token = tokenRepository.findByUserEmail(member.get().getEmail());
        DefaultAssert.isTrue(token.isPresent(), "토큰이 유효하지 않습니다.");

        memberRepository.delete(member.get());
        tokenRepository.delete(token.get());

        ApiResponse apiResponse = ApiResponse.builder().check(true).information(Message.builder().message("회원 탈퇴하셨습니다.").build()).build();

        return ResponseEntity.ok(apiResponse);
    }

    public ResponseEntity<?> modify(UserPrincipal userPrincipal, ChangePasswordRequest passwordChangeRequest){
        Optional<Member> member = memberRepository.findById(userPrincipal.getId());
        boolean passwordCheck = passwordEncoder.matches(passwordChangeRequest.getOldPassword(),member.get().getPassword());
        DefaultAssert.isTrue(passwordCheck, "잘못된 비밀번호 입니다.");

        boolean newPasswordCheck = passwordChangeRequest.getNewPassword().equals(passwordChangeRequest.getReNewPassword());
        DefaultAssert.isTrue(newPasswordCheck, "신규 등록 비밀번호 값이 일치하지 않습니다.");


        passwordEncoder.encode(passwordChangeRequest.getNewPassword());

        return ResponseEntity.ok(true);
    }

    public ResponseEntity<?> signin(SignInRequest signInRequest){
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        signInRequest.getUserId(),
                        signInRequest.getUserPwd()
                )
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);

        TokenMapping tokenMapping = customTokenProviderService.createToken(authentication);
        Token token = Token.builder()
                .refreshToken(tokenMapping.getRefreshToken())
                .userEmail(tokenMapping.getUserEmail())
                .build();
        tokenRepository.save(token);
        AuthResponse authResponse = AuthResponse.builder().
                accessToken(tokenMapping.getAccessToken()).
                refreshToken(token.getRefreshToken())
                .build();
        System.out.println("authResponse = " + authResponse);
        return ResponseEntity.ok(authResponse);
    }

    public ResponseEntity<?> signup(SignUpRequest signUpRequest, @RequestPart(value="file",required = false) MultipartFile file) throws IOException {
        DefaultAssert.isTrue(!memberRepository.existsByEmail(signUpRequest.getEmail()), "해당 이메일이 이미 존재합니다.");
        ProfileImageDto profileImageDto = new ProfileImageDto();
        String imgPath = s3Service.upload(file);

        profileImageDto.setImgFullPath(CLOUD_FRONT_DOMAIN_NAME+"/"+imgPath);
        String imageAddress = imageService.saveMember(profileImageDto);
        Member member = Member.builder()
                .username(signUpRequest.getName())
                .email(signUpRequest.getEmail())
                .password(passwordEncoder.encode(signUpRequest.getPassword()))
                .provider(Provider.local)
                .authority(Authority.USER)
                .imageUrl(imageAddress)
                .grade(Grade.Beginner)
                .birth(signUpRequest.getBirth())
                .phone(signUpRequest.getPhone())
                .build();



        Member savedMember = memberRepository.save(member);

        URI location = ServletUriComponentsBuilder
                .fromCurrentContextPath().path("/auth/")
                .buildAndExpand(member.getId()).toUri();
        ApiResponse apiResponse = ApiResponse.builder().check(true).information(Message.builder().message("회원가입에 성공하였습니다.").build()).build();
        System.out.println("apiResponse = " + apiResponse);
        return ResponseEntity.created(location).body(apiResponse);
    }

    public ResponseEntity<?> refresh(RefreshTokenRequest tokenRefreshRequest){
        System.out.println("검증 전");
        //1차 검증
        boolean checkValid = valid(tokenRefreshRequest.getRefreshToken());
        System.out.println("검증 후");

        DefaultAssert.isAuthentication(checkValid);

        Optional<Token> token = tokenRepository.findByRefreshToken(tokenRefreshRequest.getRefreshToken());
        Authentication authentication = customTokenProviderService.getAuthenticationByEmail(token.get().getUserEmail());

        //4. refresh token 정보 값을 업데이트 한다.
        //시간 유효성 확인
        TokenMapping tokenMapping;

        Long expirationTime = customTokenProviderService.getExpiration(tokenRefreshRequest.getRefreshToken());
        System.out.println("expirationTime = " + expirationTime);
        if(expirationTime > 0){
            tokenMapping = customTokenProviderService.createToken(authentication);
            //tokenMapping = customTokenProviderService.refreshToken(authentication, token.get().getRefreshToken());
        }else{ // 만료되면 위 검증에서 예외 처리 되므로 오지도 않음
            tokenMapping = customTokenProviderService.createToken(authentication);
        }

        Token updateToken = token.get().updateRefreshToken(tokenMapping.getRefreshToken());
        tokenRepository.save(updateToken);

        AuthResponse authResponse = AuthResponse.builder().accessToken(tokenMapping.getAccessToken()).refreshToken(updateToken.getRefreshToken()).build();

        return ResponseEntity.ok(authResponse);
    }

    public ResponseEntity<?> signout(RefreshTokenRequest tokenRefreshRequest){
        boolean checkValid = valid(tokenRefreshRequest.getRefreshToken());
        DefaultAssert.isAuthentication(checkValid);

        //4 token 정보를 삭제한다.
        Optional<Token> token = tokenRepository.findByRefreshToken(tokenRefreshRequest.getRefreshToken());
        tokenRepository.delete(token.get());
        ApiResponse apiResponse = ApiResponse.builder().check(true).information(Message.builder().message("로그아웃 하였습니다.").build()).build();

        return ResponseEntity.ok(apiResponse);
    }

    private boolean valid(String refreshToken){

        //1. 토큰 형식 물리적 검증
        boolean validateCheck = customTokenProviderService.validateRefreshToken(refreshToken);
        DefaultAssert.isTrue(validateCheck, "Token 검증에 실패하였습니다.");

        //2. refresh token 값을 불러온다.
        Optional<Token> token = tokenRepository.findByRefreshToken(refreshToken);
        DefaultAssert.isTrue(token.isPresent(), "탈퇴 처리된 회원입니다.");

        //3. email 값을 통해 인증값을 불러온다
        Authentication authentication = customTokenProviderService.getAuthenticationByEmail(token.get().getUserEmail());
        DefaultAssert.isTrue(token.get().getUserEmail().equals(authentication.getName()), "사용자 인증에 실패하였습니다.");

        return true;
    }

    public boolean checkId(String email) {
        return memberRepository.existsByEmail(email);
    }
}
